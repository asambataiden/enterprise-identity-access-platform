#!/usr/bin/env python3
"""Offline safety tests for the separate IAM lab approver fixtures."""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent

def load(name, module_name):
    spec = importlib.util.spec_from_file_location(module_name, ROOT / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

approver = load("provision-synthetic-approver.py", "iam_local_approver_test")
scope = load("ensure-decision-scope.py", "iam_local_scope_test")

class ApproverGuardTests(unittest.TestCase):
    def test_invalid_tenant_rejected_without_sql(self):
        for bad in ("", "not-a-uuid", None):
            with self.assertRaisesRegex(RuntimeError, "IAM_LAB_TENANT_ID"):
                approver.require_tenant(bad)

    def test_admin_login_requires_explicit_credentials(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "IAM_LAB_ADMIN_USERNAME"):
                approver.admin_token()
            with self.assertRaisesRegex(RuntimeError, "IAM_LAB_ADMIN_USERNAME"):
                scope.admin_token()

    def test_revoke_does_not_create_missing_approver(self):
        with patch.object(approver.kc, "call", return_value=[] ) as call:
            with self.assertRaisesRegex(RuntimeError, "refusing to create"):
                approver.provision_user("synthetic-admin-token", approver.require_tenant(
                    "8230545d-3560-41e6-af0b-b1ef50778606"), False, allow_create=False)
        self.assertEqual(call.call_count, 1, "Only a lookup, no Keycloak POST allowed")

    def test_scope_reconciliation_is_fixed_to_one_client_and_realm(self):
        self.assertEqual(scope.SCOPE, "access:decide")
        self.assertEqual(scope.CLIENT, "iam-lab-cli")
        self.assertEqual(scope.kc.BASE, "http://127.0.0.1:8281")
        self.assertEqual(approver.fixture.DB_CONTAINER, "keycloak-iam-lab-db-1")

if __name__ == "__main__":
    unittest.main()
