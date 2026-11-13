#!/usr/bin/env python3
"""Offline tests for the opt-in Keycloak self-requester binding assertion."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid

path = Path(__file__).with_name("oidc-pkce-smoke.py")
spec = importlib.util.spec_from_file_location("iam_pkce_subject_test", path)
pkce = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pkce)

TENANT = "8230545d-3560-41e6-af0b-b1ef50778606"
REQUEST = str(uuid.uuid5(uuid.UUID(TENANT), "iam-lab-03d-self-approval-request"))


class SubjectBindingTest(unittest.TestCase):
    def test_rejects_wrong_issuer_without_db_access(self):
        claims = {"iss": "https://evil.example", "tenant_id": TENANT, "sub": "synthetic"}
        with patch.object(pkce.importlib.util, "spec_from_file_location") as load:
            with self.assertRaisesRegex(RuntimeError, "issuer or tenant"):
                pkce.assert_self_requester_binding(claims, TENANT, REQUEST)
            load.assert_called_once()  # fixture may load, but no SQL called

    def test_rejects_wrong_tenant(self):
        claims = {"iss": pkce.ISSUER, "tenant_id": str(uuid.uuid4()), "sub": "synthetic"}
        with self.assertRaisesRegex(RuntimeError, "issuer or tenant"):
            pkce.assert_self_requester_binding(claims, TENANT, REQUEST)

    def test_rejects_empty_subject(self):
        with self.assertRaisesRegex(RuntimeError, "opaque Keycloak subject"):
            pkce.assert_self_requester_binding(
                {"iss": pkce.ISSUER, "tenant_id": TENANT, "sub": ""}, TENANT, REQUEST)

    def test_rejects_non_canonical_request(self):
        with self.assertRaisesRegex(RuntimeError, "Unexpected self-approval request"):
            pkce.assert_self_requester_binding(
                {"iss": pkce.ISSUER, "tenant_id": TENANT, "sub": "synthetic"},
                TENANT, str(uuid.uuid4()))

    def test_requires_expected_tenant(self):
        with self.assertRaisesRegex(RuntimeError, "Set IAM_LAB_TENANT_ID"):
            pkce.assert_self_requester_binding(
                {"iss": pkce.ISSUER, "tenant_id": TENANT, "sub": "synthetic"}, None, REQUEST)


if __name__ == "__main__":
    unittest.main()
