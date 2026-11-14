"""No real credentials needed: deterministic safety/behavior tests for the local PKCE probe."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "iam_bearer_boundary", Path(__file__).with_name("bearer-boundary.py")
)
boundary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(boundary)

TOKEN = "sampleHeader.samplePayload.sampleSignature"


class BearerBoundaryTest(unittest.TestCase):
    def test_signature_mutation_preserves_claims_and_changes_signature(self):
        updated = boundary.corrupted_signature(TOKEN)
        a = TOKEN.split(".")
        b = updated.split(".")
        self.assertEqual(a[:2], b[:2])
        self.assertNotEqual(a[2], b[2])
        self.assertEqual(len(a[2]), len(b[2]))

    def test_malformed_jwt_rejected(self):
        for value in ("", "abc", "a.b", ".b.c", "a.b.", "a..c"):
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    boundary.corrupted_signature(value)

    def test_only_exact_loopback_origin_accepted(self):
        for url in (
            "https://example.com", "http://example.com:8082",
            "http://127.0.0.1:8083", "http://localhost:8080",
            "http://user:pass@localhost:8082", "http://localhost:8082/?token=x",
            "http://localhost:8082/#s", "http://localhost:8082/redirect",
            "http://127.0.0.1:bad", "http://localhost:8082.evil.example"):
            with self.subTest(url=url):
                with self.assertRaises(ValueError):
                    boundary.validated_endpoint(url)
        self.assertEqual(
            boundary.validated_endpoint("http://127.0.0.1:8082/api/v1/access-requests"),
            "http://127.0.0.1:8082/api/v1/provisioning/quarantine")
        self.assertEqual(
            boundary.validated_endpoint("http://localhost:8082/"),
            "http://localhost:8082/api/v1/provisioning/quarantine")

    def test_probe_order_and_authentication_denied_by_local_scope(self):
        with patch.object(boundary, "readonly_status", side_effect=[401, 401, 403]) as http:
            boundary.verify_real_bearer_boundary("http://127.0.0.1:8082", TOKEN)
        self.assertEqual(http.call_count, 3)
        self.assertIsNone(http.call_args_list[0].args[1])
        self.assertNotEqual(http.call_args_list[1].args[1], TOKEN)
        self.assertEqual(http.call_args_list[2].args[1], TOKEN)
        self.assertTrue(all(x.args[0].endswith(boundary.READ_PATH) for x in http.call_args_list))

    def test_authorized_real_token_can_return_200(self):
        with patch.object(boundary, "readonly_status", side_effect=[401, 401, 200]):
            boundary.verify_real_bearer_boundary("http://localhost:8082", TOKEN)

    def test_unexpected_http_status_fails_closed(self):
        for statuses in ((200, 401, 403), (401, 403, 403),
                         (401, 401, 401), (401, 401, 302)):
            with self.subTest(statuses=statuses):
                with patch.object(boundary, "readonly_status", side_effect=statuses):
                    with self.assertRaises(RuntimeError):
                        boundary.verify_real_bearer_boundary("http://127.0.0.1:8082", TOKEN)

    def test_redirects_never_forward_authorization(self):
        import urllib.request
        req = urllib.request.Request(
            "http://127.0.0.1:8082" + boundary.READ_PATH,
            headers={"Authorization": "Bearer opaque"})
        self.assertIsNone(boundary.NoRedirect().redirect_request(
            req, None, 302, "Moved", {}, "http://example.com/steal"))


if __name__ == "__main__":
    unittest.main()
