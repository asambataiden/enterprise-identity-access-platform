#!/usr/bin/env python3
"""Offline tests for the dedicated reviewer PKCE subject binding assertion."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("iam_review_subject_test", ROOT / "oidc-pkce-smoke.py")
pkce = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pkce)
TENANT = "8230545d-3560-41e6-af0b-b1ef50778606"
REVIEWER = str(uuid.uuid5(uuid.UUID(TENANT), "iam-lab-test-quarantine-reviewer:employee"))


def claims(**overrides):
    result = {"iss": pkce.ISSUER, "tenant_id": TENANT, "sub": "opaque-reviewer-subject"}
    result.update(overrides)
    return result


class FakeFixture:
    def __init__(self, results):
        self.results = iter(results)
        self.statements = []

    def psql(self, sql):
        self.statements.append(sql)
        return next(self.results)


class ReviewerBindingTests(unittest.TestCase):
    def test_rejects_missing_expected_tenant(self):
        with self.assertRaisesRegex(RuntimeError, "Set IAM_LAB_TENANT_ID"):
            pkce.assert_quarantine_reviewer_binding(claims(), None)

    def test_rejects_wrong_issuer(self):
        with self.assertRaisesRegex(RuntimeError, "issuer mismatch"):
            pkce.assert_quarantine_reviewer_binding(claims(iss="https://invalid.example"), TENANT)

    def test_rejects_wrong_tenant(self):
        with self.assertRaisesRegex(RuntimeError, "tenant mismatch"):
            pkce.assert_quarantine_reviewer_binding(claims(tenant_id=str(uuid.uuid4())), TENANT)

    def test_rejects_noncanonical_expected_tenant(self):
        with self.assertRaisesRegex(RuntimeError, "tenant mismatch"):
            pkce.assert_quarantine_reviewer_binding(claims(), str(uuid.uuid4()))

    def test_rejects_invalid_subject(self):
        for subject in ("", None, 42, "x" * 256):
            with self.subTest(subject=type(subject).__name__), self.assertRaisesRegex(RuntimeError, "Invalid reviewer subject"):
                pkce.assert_quarantine_reviewer_binding(claims(sub=subject), TENANT)

    def test_positive_grant_assertion(self):
        fake = FakeFixture(["1", "1"])
        from types import SimpleNamespace
        with patch("importlib.util.spec_from_file_location", return_value=SimpleNamespace(loader=SimpleNamespace(exec_module=lambda target: None))), patch("importlib.util.module_from_spec", return_value=fake), patch("builtins.print"):
            pkce.assert_quarantine_reviewer_binding(claims(), TENANT, expect_grant=True)
        self.assertEqual(len(fake.statements), 2)
        self.assertIn(REVIEWER, fake.statements[0])
        self.assertIn("PROVISIONING_QUARANTINE_REVIEW", fake.statements[1])

    def test_negative_grant_assertion(self):
        fake = FakeFixture(["1", "0"])
        from types import SimpleNamespace
        with patch("importlib.util.spec_from_file_location", return_value=SimpleNamespace(loader=SimpleNamespace(exec_module=lambda target: None))), patch("importlib.util.module_from_spec", return_value=fake), patch("builtins.print"):
            pkce.assert_quarantine_reviewer_binding(claims(), TENANT, expect_grant=False)
        self.assertEqual(len(fake.statements), 2)

    def test_rejects_wrong_subject_mapping(self):
        fake = FakeFixture(["0"])
        from types import SimpleNamespace
        with patch("importlib.util.spec_from_file_location", return_value=SimpleNamespace(loader=SimpleNamespace(exec_module=lambda target: None))), patch("importlib.util.module_from_spec", return_value=fake):
            with self.assertRaisesRegex(RuntimeError, "not the active synthetic"):
                pkce.assert_quarantine_reviewer_binding(claims(), TENANT)
        self.assertEqual(len(fake.statements), 1)

    def test_rejects_grant_mismatch(self):
        fake = FakeFixture(["1", "0"])
        from types import SimpleNamespace
        with patch("importlib.util.spec_from_file_location", return_value=SimpleNamespace(loader=SimpleNamespace(exec_module=lambda target: None))), patch("importlib.util.module_from_spec", return_value=fake):
            with self.assertRaisesRegex(RuntimeError, "permission state"):
                pkce.assert_quarantine_reviewer_binding(claims(), TENANT, expect_grant=True)


if __name__ == "__main__":
    unittest.main()
