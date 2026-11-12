#!/usr/bin/env python3
"""Offline unit tests; never connect to Docker or alter a real IAM lab."""
import importlib.util
from pathlib import Path
import unittest
import uuid
from unittest.mock import patch

path = Path(__file__).with_name("self-approval-guard.py")
spec = importlib.util.spec_from_file_location("iam_self_approval_guard_tests", path)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)

TENANT = uuid.UUID("8230545d-3560-41e6-af0b-b1ef50778606")


class SelfApprovalLabGuardTest(unittest.TestCase):
    def test_deterministic_requester_is_self_requester_not_approver(self):
        requester, approver, request_id = guard.identity(TENANT)
        self.assertNotEqual(requester, approver)
        self.assertEqual(requester, uuid.uuid5(TENANT, "iam-lab-test-admin:employee"))
        self.assertEqual(request_id, uuid.uuid5(TENANT, "iam-lab-03d-self-approval-request"))

    def test_grant_preconditions_fail_closed_on_unbound_requester(self):
        requester, approver, request_id = guard.identity(TENANT)
        with patch.object(guard.fixture, "psql", return_value="0") as run:
            with self.assertRaisesRegex(RuntimeError, "active, Keycloak-bound"):
                guard.check_preconditions(TENANT, requester, approver, request_id)
            self.assertEqual(run.call_count, 1)

    def test_grant_preconditions_check_all_four_guards(self):
        requester, approver, request_id = guard.identity(TENANT)
        with patch.object(guard.fixture, "psql", side_effect=["1", "1", "0", "0"]) as run:
            guard.check_preconditions(TENANT, requester, approver, request_id)
            self.assertEqual(run.call_count, 4)
            self.assertIn("status='PENDING'", run.call_args_list[1].args[0])
            self.assertIn("ACCESS_REQUEST_CREATE", run.call_args_list[2].args[0])
            self.assertIn("ACCESS_REQUEST_DECIDE", run.call_args_list[3].args[0])

    def test_grant_rejects_existing_separate_approver_permission(self):
        requester, approver, request_id = guard.identity(TENANT)
        with patch.object(guard.fixture, "psql", side_effect=["1", "1", "0", "1"]):
            with self.assertRaisesRegex(RuntimeError, "Separate approver"):
                guard.check_preconditions(TENANT, requester, approver, request_id)

    def test_grant_only_writes_requester_decide(self):
        requester, _, _ = guard.identity(TENANT)
        with patch.object(guard.fixture, "psql", side_effect=["", "1"]) as run:
            self.assertTrue(guard.change(TENANT, requester, False))
            sql = run.call_args_list[0].args[0]
            self.assertIn("INSERT INTO employee_permissions", sql)
            self.assertIn("'ACCESS_REQUEST_DECIDE'", sql)
            self.assertIn(str(requester), sql)
            self.assertNotIn("ACCESS_REQUEST_CREATE", sql)

    def test_revoke_only_deletes_requester_decide(self):
        requester, _, _ = guard.identity(TENANT)
        with patch.object(guard.fixture, "psql", side_effect=["", "0"]) as run:
            self.assertFalse(guard.change(TENANT, requester, True))
            sql = run.call_args_list[0].args[0]
            self.assertIn("DELETE FROM employee_permissions", sql)
            self.assertIn("'ACCESS_REQUEST_DECIDE'", sql)
            self.assertNotIn("ACCESS_REQUEST_CREATE", sql)

    def test_readback_mismatch_is_failure(self):
        requester, _, _ = guard.identity(TENANT)
        with patch.object(guard.fixture, "psql", side_effect=["", "0"]):
            with self.assertRaisesRegex(RuntimeError, "readback mismatch"):
                guard.change(TENANT, requester, False)


if __name__ == "__main__":
    unittest.main()
