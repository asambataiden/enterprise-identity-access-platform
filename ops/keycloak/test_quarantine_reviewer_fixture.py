#!/usr/bin/env python3
"""Offline guard tests. No Docker, database or Keycloak network operations."""
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parent


def load(filename, name):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


reviewer = load("provision-synthetic-quarantine-reviewer.py", "iam_review_fixture_test")
scope = load("ensure-quarantine-review-scope.py", "iam_review_scope_test")
smoke = load("oidc-pkce-smoke.py", "iam_review_smoke_test")
TENANT = uuid.UUID("8230545d-3560-41e6-af0b-b1ef50778606")


class ReviewerFixtureGuardTest(unittest.TestCase):
    def test_review_scope_is_isolated_optional_client_scope(self):
        self.assertEqual(scope.SCOPE, "provisioning:review")
        self.assertEqual(scope.CLIENT, "iam-lab-cli")
        self.assertEqual(scope.kc.BASE, "http://127.0.0.1:8281")
        self.assertEqual(reviewer.fixture.DB_CONTAINER, "keycloak-iam-lab-db-1")

    def test_reviewer_is_not_requester_or_approver(self):
        reviewer_id = uuid.uuid5(TENANT, reviewer.USER + ":employee")
        self.assertNotEqual(reviewer_id, uuid.uuid5(TENANT, "iam-lab-test-admin:employee"))
        self.assertNotEqual(reviewer_id, uuid.uuid5(TENANT, "iam-lab-test-approver:employee"))

    def test_missing_admin_credentials_fail_closed(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "IAM_LAB_ADMIN_USERNAME"):
                reviewer.admin_token()
            with self.assertRaisesRegex(RuntimeError, "IAM_LAB_ADMIN_USERNAME"):
                scope.admin_token()

    def test_revoke_cannot_create_reviewer_account(self):
        with patch.object(reviewer.kc, "call", return_value=[]) as call:
            with self.assertRaisesRegex(RuntimeError, "refusing to create"):
                reviewer.provision_user("non-secret-mock-token", TENANT, False, allow_create=False)
            self.assertEqual(call.call_count, 1)

    def test_quarantine_pkce_mode_exposes_read_only_flag(self):
        source = (ROOT / "oidc-pkce-smoke.py").read_text(encoding="utf-8")
        self.assertIn('--quarantine-list', source)
        self.assertIn('method="GET" if args.quarantine_list else "POST"', source)
        self.assertIn('"/api/v1/provisioning/quarantine"', source)
        self.assertIn('scope != "provisioning:review"', source)


if __name__ == "__main__":
    unittest.main()
