"""OpenAPI contract integrity guard for the IAM public HTTP surface.

Standard-library only. CI already discovers ops/security/test_*.py.
This is a source-level contract guard, not an HTTP runtime OpenAPI test.
"""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "docs/api/openapi.json"
SRC = ROOT / "src/main/java/dev/asambataiden/iam"

EXPECTED = {
    ("/api/v1/access-requests", "post"): (
        "accessrequest/AccessRequestController.java",
        '"/api/v1/access-requests"',
        "@PostMapping(",
        "SCOPE_access:request",
        "ACCESS_REQUEST_CREATE",
    ),
    ("/api/v1/access-requests/{requestId}/decision", "post"): (
        "accessrequest/ApprovalDecisionController.java",
        '"/{requestId}/decision"',
        "@PostMapping(",
        "SCOPE_access:decide",
        "ACCESS_REQUEST_DECIDE",
    ),
    ("/api/v1/provisioning/quarantine", "get"): (
        "provisioning/quarantine/QuarantineInspectionController.java",
        '"/api/v1/provisioning/quarantine"',
        "@GetMapping",
        "SCOPE_provisioning:review",
        "PROVISIONING_QUARANTINE_REVIEW",
    ),
    ("/api/v1/provisioning/quarantine/{requestId}", "get"): (
        "provisioning/quarantine/QuarantineInspectionController.java",
        '"/{requestId}"',
        "@GetMapping",
        "SCOPE_provisioning:review",
        "PROVISIONING_QUARANTINE_REVIEW",
    ),
}


def read_spec():
    return json.loads(SPEC.read_text(encoding="utf-8"))


class OpenApiContractTest(unittest.TestCase):
    def test_format_and_exact_public_surface(self):
        spec = read_spec()
        self.assertEqual("3.1.0", spec["openapi"])
        actual = {
            (path, method)
            for path, methods in spec["paths"].items()
            for method in methods
            if method.lower() in {"get", "post", "put", "patch", "delete", "head", "options"}
        }
        self.assertEqual(set(EXPECTED), actual)
        self.assertEqual(
            {"/api/v1/access-requests", "/api/v1/access-requests/{requestId}/decision",
             "/api/v1/provisioning/quarantine", "/api/v1/provisioning/quarantine/{requestId}"},
            set(spec["paths"]),
        )

    def test_security_is_explicit_and_consistent_with_java(self):
        spec = read_spec()
        cfg = (SRC / "bootstrap/BootstrapSecurityConfiguration.java").read_text(encoding="utf-8")
        self.assertIn(".anyRequest().denyAll()", cfg)
        self.assertIn('"/actuator/health"', cfg)
        for (path, method), (source, java_path, annotation, scope, permission) in EXPECTED.items():
            with self.subTest(path=path, method=method):
                op = spec["paths"][path][method]
                self.assertEqual([{"bearerAuth": []}], op["security"])
                self.assertEqual(scope, op["x-required-authority"])
                self.assertEqual(permission, op["x-local-permission"])
                self.assertIn('hasAuthority("' + scope + '")', cfg)
                code = (SRC / source).read_text(encoding="utf-8")
                self.assertIn(java_path, code)
                self.assertIn(annotation, code)
        scheme = spec["components"]["securitySchemes"]["bearerAuth"]
        self.assertEqual(("http", "bearer", "JWT"),
                         (scheme["type"], scheme["scheme"], scheme["bearerFormat"]))

    def test_no_client_writable_privileged_actor_identity(self):
        spec = read_spec()
        fields = spec["components"]["schemas"]["CreateAccessRequest"]["properties"]
        self.assertEqual({"employeeId", "applicationId", "roleKey"}, set(fields))
        self.assertNotIn("tenantId", fields)
        self.assertNotIn("requesterId", fields)
        create = spec["paths"]["/api/v1/access-requests"]["post"]
        required_headers = [p for p in create["parameters"] if p["in"] == "header" and p["required"]]
        self.assertEqual(["Idempotency-Key"], [p["name"] for p in required_headers])
        self.assertEqual(128, required_headers[0]["schema"]["maxLength"])
        self.assertEqual(64, fields["roleKey"]["maxLength"])
        self.assertIn('"Idempotency-Key"', (SRC / EXPECTED[("/api/v1/access-requests", "post")][0]).read_text(encoding="utf-8"))

    def test_decision_and_quarantine_are_not_executable_provisioning(self):
        spec = read_spec()
        decision = spec["components"]["schemas"]["DecisionRequest"]["properties"]["decision"]["enum"]
        self.assertEqual(["APPROVED", "REJECTED"], decision)
        self.assertEqual(["APPROVED", "REJECTED"],
                         spec["components"]["schemas"]["DecisionResponse"]["properties"]["decision"]["enum"])
        limit = spec["paths"]["/api/v1/provisioning/quarantine"]["get"]["parameters"][0]
        self.assertEqual(("limit", 1, 100, 20),
                         (limit["name"], limit["schema"]["minimum"], limit["schema"]["maximum"],
                          limit["schema"]["default"]))
        for path, ops in spec["paths"].items():
            self.assertNotRegex(path, re.compile(r"outbox|inbox|lease|requeue|admin|target"))
            if path.startswith("/api/v1/provisioning/quarantine"):
                self.assertEqual({"get"}, set(ops))

    def test_response_codes_and_response_model_fields(self):
        spec = read_spec()
        create = spec["paths"]["/api/v1/access-requests"]["post"]
        self.assertTrue({"201", "400", "401", "403", "404", "409"} <= set(create["responses"]))
        self.assertIn("Location", create["responses"]["201"]["headers"])
        decision = spec["paths"]["/api/v1/access-requests/{requestId}/decision"]["post"]
        self.assertTrue({"200", "400", "401", "403", "404", "409"} <= set(decision["responses"]))
        r = spec["components"]["schemas"]["AccessRequest"]["properties"]
        self.assertEqual({"id", "tenantId", "requesterId", "employeeId", "applicationId",
                          "roleKey", "idempotencyKey", "status", "createdAt"}, set(r))
        s = spec["components"]["schemas"]["QuarantineSnapshot"]["properties"]
        self.assertEqual({"tenantId", "requestId", "state", "attempts", "lastErrorCode",
                          "nextAttemptAt", "leaseExpiresAt", "startedAttempts", "mockVerified",
                          "mockAbsent", "mockUnknown"}, set(s))


if __name__ == "__main__":
    unittest.main()
