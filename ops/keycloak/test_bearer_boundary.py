import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "iam_bearer_boundary", Path(__file__).with_name("bearer-boundary.py")
)
boundary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(boundary)

TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.TESTSIGNATURE"


class RealBearerBoundaryTest(unittest.TestCase):
    def test_signature_mutation_preserves_header_payload_and_alters_signature(self):
        tampered = boundary.corrupted_signature(TOKEN)
        old = TOKEN.split(".")
        new = tampered.split(".")
        self.assertEqual(old[:2], new[:2])
        self.assertNotEqual(old[2], new[2])
        self.assertEqual(len(old[2]), len(new[2]))

    def test_malformed_access_token_rejected(self):
        for value in ("", "abc", "a.b", "a..c", "a.b."):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    boundary.corrupted_signature(value)

    def test_anonymous_and_corrupted_denial_then_authenticated_forbidden(self):
        with patch.object(boundary, "readonly_status", side_effect=[401, 401, 403]) as probe:
            boundary.verify_real_bearer_boundary("http://127.0.0.1:8082/api/v1/access-requests", TOKEN)
        self.assertEqual(probe.call_count, 3)
        args = [call.args for call in probe.call_args_list]
        self.assertEqual(args[0][1], None)
        self.assertNotEqual(args[1][1], TOKEN)
        self.assertEqual(args[2][1], TOKEN)
        self.assertTrue(all(x[0] == "http://127.0.0.1:8082/api/v1/provisioning/quarantine" for x in args))

    def test_authenticated_ok_is_also_valid(self):
        with patch.object(boundary, "readonly_status", side_effect=[401, 401, 200]):
            boundary.verify_real_bearer_boundary("http://localhost:8082/api/v1/access-requests", TOKEN)

    def test_corrupted_signature_accepted_is_failure(self):
        with patch.object(boundary, "readonly_status", side_effect=[401, 403, 403]):
            with self.assertRaisesRegex(RuntimeError, "Corrupted-signature"):
                boundary.verify_real_bearer_boundary("http://127.0.0.1:8082", TOKEN)

    def test_authentic_token_not_authenticated_is_failure(self):
        with patch.object(boundary, "readonly_status", side_effect=[401, 401, 401]):
            with self.assertRaisesRegex(RuntimeError, "not recognized"):
                boundary.verify_real_bearer_boundary("http://127.0.0.1:8082", TOKEN)

    def test_unsafe_api_urls_rejected_before_any_request(self):
        for url in (
            "https://example.org/api/v1/access-requests",
            "http://example.org",
            "http://user:pass@localhost:8082",
            "http://localhost:8082?secret=abc",
            "http://localhost:8082/#fragment",
        ):
            with self.subTest(url=url):
                with patch.object(boundary, "readonly_status") as probe:
                    with self.assertRaises(ValueError):
                        boundary.verify_real_bearer_boundary(url, TOKEN)
                    probe.assert_not_called()


if __name__ == "__main__":
    unittest.main()
