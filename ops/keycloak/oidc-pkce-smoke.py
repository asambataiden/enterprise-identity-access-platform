#!/usr/bin/env python3
"""Local-only OIDC authorization-code + PKCE smoke client.

No password grant, client secret, token files, token stdout or JWT-as-trust.
Do not use outside the isolated iam-lab developer environment.
"""
import argparse
import base64
import hashlib
import importlib.util
import http.server
import json
import os
from pathlib import Path
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import webbrowser

ISSUER = "http://127.0.0.1:8281/realms/iam-lab"
REDIRECT = "http://127.0.0.1:8765/callback"
CLIENT_ID = "iam-lab-cli"
EXPECTED_AUD = "iam-governance-api"


def json_request(url, data=None, headers=None):
    request = urllib.request.Request(url, data=data, headers=headers or {})
    with urllib.request.urlopen(request, timeout=12) as response:
        return json.load(response)


def opaque_claims(token):
    # Parse only to report configuration errors. This does not verify a signature.
    pieces = token.split(".")
    if len(pieces) != 3:
        raise ValueError("OIDC provider returned a non-JWT access token")
    payload = pieces[1] + "=" * (-len(pieces[1]) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def assert_self_requester_binding(claims, expected_tenant, decision_request_id):
    """Read-only guarded lab checks; decoded JWT alone is NOT trusted as a signature."""
    fixture_path = Path(__file__).resolve().with_name("seed-authorized-iam-fixture.py")
    spec = importlib.util.spec_from_file_location("iam_self_binding_fixture", fixture_path)
    fixture = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixture)
    if not expected_tenant:
        raise RuntimeError("Set IAM_LAB_TENANT_ID for requester identity assertion")
    try:
        tenant = uuid.UUID(expected_tenant)
        asserted_tenant = uuid.UUID(claims.get("tenant_id", ""))
        request = uuid.UUID(decision_request_id)
    except (ValueError, TypeError, AttributeError):
        raise RuntimeError("Invalid synthetic tenant, token tenant or request identifier") from None
    if tenant != uuid.UUID("8230545d-3560-41e6-af0b-b1ef50778606"):
        raise RuntimeError("Refusing self-approval assertion outside canonical synthetic tenant")
    if asserted_tenant != tenant or claims.get("iss") != ISSUER:
        raise RuntimeError("Keycloak issuer or tenant does not match synthetic requester fixture")
    subject = claims.get("sub")
    if not isinstance(subject, str) or not subject or len(subject) > 255:
        raise RuntimeError("Invalid opaque Keycloak subject")
    # Never log the subject or decode bearer-token payload into operator output.
    escaped_subject = subject.replace("'", "''")
    actor = uuid.uuid5(tenant, "iam-lab-test-admin:employee")
    expected_request = uuid.uuid5(tenant, "iam-lab-03d-self-approval-request")
    if request != expected_request:
        raise RuntimeError("Unexpected self-approval request identifier")
    sql = f"""SELECT count(*) FROM employee_identities i
JOIN employees e ON e.tenant_id=i.tenant_id AND e.id=i.employee_id
JOIN access_requests ar ON ar.tenant_id=e.tenant_id AND ar.requester_id=e.id
JOIN employee_permissions p ON p.tenant_id=e.tenant_id AND p.employee_id=e.id
WHERE i.tenant_id='{tenant}' AND i.employee_id='{actor}'
AND i.issuer='{ISSUER}' AND i.subject='{escaped_subject}'
AND e.lifecycle_status='ACTIVE'
AND ar.id='{request}' AND ar.status='PENDING'
AND p.permission_key='ACCESS_REQUEST_DECIDE';"""
    if fixture.psql(sql).strip() != "1":
        raise RuntimeError("Browser principal is not the authorized synthetic requester; no decision submitted")
    print("PASS: token subject maps to the requester's active tenant employee with DECIDE grant (diagnostic DB readback)")


def assert_quarantine_reviewer_binding(claims, expected_tenant, expect_grant=True):
    """Fail closed unless this lab token maps to the exact synthetic reviewer.

    Decoded claims are diagnostic only; the Java API verifies token signatures.
    """
    if not expected_tenant:
        raise RuntimeError("Set IAM_LAB_TENANT_ID for reviewer assertion")
    canonical = uuid.UUID("8230545d-3560-41e6-af0b-b1ef50778606")
    try:
        tenant = uuid.UUID(expected_tenant)
        token_tenant = uuid.UUID(claims.get("tenant_id", ""))
    except (ValueError, TypeError, AttributeError):
        raise RuntimeError("Invalid reviewer tenant identity") from None
    if tenant != canonical or token_tenant != tenant:
        raise RuntimeError("Reviewer tenant mismatch")
    if claims.get("iss") != ISSUER:
        raise RuntimeError("Reviewer issuer mismatch")
    subject = claims.get("sub")
    if not isinstance(subject, str) or not subject or len(subject) > 255:
        raise RuntimeError("Invalid reviewer subject")

    reviewer = uuid.uuid5(tenant, "iam-lab-test-quarantine-reviewer:employee")
    fixture_path = Path(__file__).resolve().with_name("seed-authorized-iam-fixture.py")
    spec = importlib.util.spec_from_file_location("iam_review_binding_fixture", fixture_path)
    fixture = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixture)
    # Fixed lab identifiers and validated UUIDs; SQL escape opaque OIDC subject.
    safe_subject = subject.replace("'", "''")
    identity_sql = f"""SELECT count(*) FROM employee_identities i
JOIN employees e ON e.tenant_id=i.tenant_id AND e.id=i.employee_id
WHERE i.tenant_id='{tenant}' AND i.employee_id='{reviewer}'
AND i.issuer='{ISSUER}' AND i.subject='{safe_subject}'
AND e.lifecycle_status='ACTIVE';"""
    if fixture.psql(identity_sql).strip() != "1":
        raise RuntimeError("Authenticated principal is not the active synthetic quarantine reviewer")

    grant_sql = f"""SELECT count(*) FROM employee_permissions
WHERE tenant_id='{tenant}' AND employee_id='{reviewer}'
AND permission_key='PROVISIONING_QUARANTINE_REVIEW';"""
    grant = fixture.psql(grant_sql).strip()
    if grant != ("1" if expect_grant else "0"):
        raise RuntimeError("Reviewer permission state does not match expected acceptance scenario")
    print("PASS: token subject maps to dedicated active quarantine reviewer (diagnostic DB readback)")
    print("PASS: reviewer grant state matches expected scenario")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-url", default="http://127.0.0.1:8082/api/v1/access-requests",
                        help="Loopback IAM API URL for safe negative smoke test")
    parser.add_argument("--employee-id", help="Synthetic employee UUID (requires authorized local fixture)")
    parser.add_argument("--application-id", help="Synthetic application UUID (requires authorized local fixture)")
    parser.add_argument("--idempotency-key", help="Stable test-only key to verify retries/conflicts")
    parser.add_argument("--expect-status", type=int, choices=[200, 201, 403, 404, 409],
                        help="Fail if the real API status differs")
    parser.add_argument("--expected-tenant-id", default=os.getenv("IAM_LAB_TENANT_ID"),
                        help="Synthetic fixture tenant UUID (required for local binding diagnosis)")
    parser.add_argument("--assert-self-requester", action="store_true",
                        help="Require exact synthetic requester subject, pending request and temporary DECIDE grant before POST")
    parser.add_argument("--assert-quarantine-reviewer", action="store_true",
                        help="Assert exact synthetic reviewer identity and expected local grant")
    parser.add_argument("--diagnose-local-binding", action="store_true",
                        help="Read-only fixture DB check using token claims (diagnostic, not JWT verification)")
    parser.add_argument("--scope", choices=["access:request", "access:decide", "provisioning:review"], default="access:request",
                        help="Optional OAuth scope to request for the synthetic login")
    parser.add_argument("--quarantine-list", action="store_true",
                        help="Read-only authenticated quarantine GET; no decisions or provisioning")
    parser.add_argument("--verify-bearer-boundary", action="store_true",
                        help="After real PKCE login, perform only read-only HTTP authentication probes")
    parser.add_argument("--decision-request-id", help="Synthetic PENDING access request UUID for approval")
    parser.add_argument("--decision", choices=["APPROVED", "REJECTED"], default="APPROVED")
    parser.add_argument("--repeat-on-success", action="store_true",
                        help="Repeat authorized POST once and assert same persisted request ID")
    args = parser.parse_args()
    if args.verify_bearer_boundary and (
            args.employee_id or args.application_id or args.idempotency_key
            or args.decision_request_id or args.quarantine_list or args.repeat_on_success
            or args.expect_status is not None or args.assert_self_requester
            or args.assert_quarantine_reviewer or args.diagnose_local_binding):
        parser.error("--verify-bearer-boundary is read-only and cannot combine with mutation or fixture options")
    if args.assert_quarantine_reviewer and (not args.quarantine_list or
            args.scope != "provisioning:review" or args.expect_status not in (200, 403)):
        parser.error("--assert-quarantine-reviewer requires --quarantine-list, "
                     "--scope provisioning:review and --expect-status 200 or 403")
    if args.quarantine_list:
        if args.scope != "provisioning:review" or args.decision_request_id or args.employee_id or args.application_id or args.repeat_on_success:
            parser.error("--quarantine-list requires provisioning:review and cannot combine with POST options")
        if args.expect_status not in (200, 403):
            parser.error("--quarantine-list requires --expect-status 200 or 403")
    if args.assert_self_requester and (not args.decision_request_id or args.expect_status != 403 or args.scope != "access:decide"):
        parser.error("--assert-self-requester requires a decision request, access:decide and --expect-status 403")
    if args.decision_request_id:
        try:
            uuid.UUID(args.decision_request_id)
        except ValueError:
            parser.error("--decision-request-id must be a valid UUID")
        if args.scope != "access:decide":
            parser.error("Decision mode requires --scope access:decide")
        if args.employee_id or args.application_id or args.repeat_on_success:
            parser.error("Decision mode cannot combine creation fields or retry")
    elif args.scope == "access:decide":
        parser.error("--scope access:decide requires --decision-request-id")
    if args.repeat_on_success and args.expect_status != 201:
        parser.error("--repeat-on-success requires --expect-status 201")
    if args.idempotency_key and (len(args.idempotency_key) > 128 or
                                  not args.idempotency_key.strip() or
                                  args.idempotency_key != args.idempotency_key.strip()):
        parser.error("Invalid idempotency key")
    if (args.employee_id is None) != (args.application_id is None):
        parser.error("--employee-id and --application-id must be supplied together")
    parsed_api = urllib.parse.urlparse(args.api_url)
    if parsed_api.hostname not in ("127.0.0.1", "localhost") or parsed_api.scheme != "http":
        parser.error("API URL must use localhost HTTP in this isolated demo")

    discovery = json_request(ISSUER + "/.well-known/openid-configuration")
    if discovery.get("issuer") != ISSUER:
        raise ValueError("OIDC issuer mismatch; refusing login")
    state = secrets.token_urlsafe(32)
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()

    class Callback(http.server.BaseHTTPRequestHandler):
        result = None
        oauth_error = None

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            params = urllib.parse.parse_qs(parsed.query)
            if parsed.path != "/callback":
                self.send_error(404, "Unexpected path")
                return
            if params.get("state") != [state]:
                self.send_error(400, "Invalid OAuth state")
                return
            if "error" in params:
                # Do not render, persist or echo untrusted error descriptions.
                Callback.oauth_error = params["error"][0][:60]
            elif len(params.get("code", [])) == 1:
                Callback.result = params
            else:
                self.send_error(400, "Missing authorization code")
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"Local login callback received. Return to your terminal.")

        def log_message(self, format, *args):
            pass  # never log authorization codes or query strings

    server = http.server.HTTPServer(("127.0.0.1", 8765), Callback)
    server.timeout = 1
    params = {
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT,
        "response_type": "code",
        "scope": "openid " + args.scope,
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    url = discovery["authorization_endpoint"] + "?" + urllib.parse.urlencode(params)
    print("Opening Keycloak login in your browser (local S256 PKCE).")
    print("Use only a synthetic test user. Do not paste tokens or callback URLs.")
    try:
        if not webbrowser.open(url):
            print("Browser did not open automatically; use the local login URL manually:", url)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline and Callback.result is None and Callback.oauth_error is None:
            server.handle_request()
    finally:
        server.server_close()
    if Callback.oauth_error is not None:
        # Distinguish a Keycloak refusal from an unrelated callback or timeout.
        safe_errors = {"access_denied", "invalid_request", "unauthorized_client",
                       "unsupported_response_type", "invalid_scope", "server_error",
                       "temporarily_unavailable"}
        code = Callback.oauth_error if Callback.oauth_error in safe_errors else "unrecognized_error"
        raise RuntimeError("Keycloak returned OAuth error: " + code)
    result = Callback.result
    if not result:
        raise RuntimeError("No valid OAuth callback within 180 seconds; complete browser login while the helper is running")
    token_data = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT,
        "code": result["code"][0],
        "code_verifier": verifier,
    }).encode()
    tokens = json_request(discovery["token_endpoint"], token_data,
                          {"Content-Type": "application/x-www-form-urlencoded"})
    access_token = tokens["access_token"]
    claims = opaque_claims(access_token)
    audiences = claims.get("aud", [])
    if isinstance(audiences, str):
        audiences = [audiences]
    scopes = claims.get("scope", "").split()
    print("PASS: Keycloak authorization code exchanged using PKCE.")
    print("Informational decoded claims (signature NOT checked by this helper):")
    print("  issuer matches:", claims.get("iss") == ISSUER)
    print("  API audience present:", EXPECTED_AUD in audiences)
    print("  requested scope present:", args.scope in scopes)
    print("  tenant_id present:", bool(claims.get("tenant_id")))
    print("Access token intentionally not displayed or saved.")

    if args.verify_bearer_boundary:
        boundary_path = Path(__file__).resolve().with_name("bearer-boundary.py")
        spec = importlib.util.spec_from_file_location("iam_bearer_boundary", boundary_path)
        boundary = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(boundary)
        boundary.verify_real_bearer_boundary(args.api_url, access_token)
        return

    if args.assert_self_requester:
        assert_self_requester_binding(claims, args.expected_tenant_id, args.decision_request_id)
    if args.assert_quarantine_reviewer:
        assert_quarantine_reviewer_binding(
            claims, args.expected_tenant_id, expect_grant=(args.expect_status == 200))

    if args.diagnose_local_binding:
        # This is a controlled local diagnostic, not a production auth check.
        # JWT claims are NOT signature-verified by Python. The Java API verifies JWTs.
        fixture_path = Path(__file__).resolve().with_name("seed-authorized-iam-fixture.py")
        spec = importlib.util.spec_from_file_location("iam_lab_fixture", fixture_path)
        fixture = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fixture)
        if not args.expected_tenant_id:
            raise RuntimeError("Set IAM_LAB_TENANT_ID or --expected-tenant-id for local binding diagnosis")
        expected_tenant = uuid.UUID(args.expected_tenant_id)
        # Diagnose claim representation without disclosing identifiers or tokens.
        # OAuth/OIDC 'sub' is an opaque string; it does NOT need to be a UUID.
        tenant_claim = claims.get("tenant_id")
        subject_claim = claims.get("sub")
        print("Diagnostic: tenant_id claim type:", type(tenant_claim).__name__)
        print("Diagnostic: sub claim type:", type(subject_claim).__name__)
        try:
            actual_tenant = uuid.UUID(tenant_claim) if isinstance(tenant_claim, str) else None
        except ValueError:
            actual_tenant = None
        print("Diagnostic: tenant_id is UUID string:", actual_tenant is not None)
        print("Diagnostic: sub is nonempty string:",
              isinstance(subject_claim, str) and bool(subject_claim))
        if actual_tenant is None:
            raise RuntimeError("tenant_id is not a UUID string; inspect Keycloak protocol mapper configuration")
        if not isinstance(subject_claim, str) or not subject_claim or len(subject_claim) > 255:
            raise RuntimeError("sub is not an acceptable opaque subject string")
        # Quote escaping is required because OIDC subjects are opaque strings.
        safe_subject = subject_claim.replace("'", "''")
        issuer = claims.get("iss")
        if issuer != ISSUER:
            raise RuntimeError("Token issuer differs from local expected issuer")
        print("Diagnostic: token tenant matches fixture:", actual_tenant == expected_tenant)
        if actual_tenant != expected_tenant:
            raise RuntimeError("Token tenant does not match the provisioned synthetic fixture tenant")
        sql = ("""
SELECT count(*)
FROM employee_identities i
JOIN employees e ON e.tenant_id=i.tenant_id AND e.id=i.employee_id
JOIN employee_permissions p
  ON p.tenant_id=e.tenant_id AND p.employee_id=e.id
WHERE i.tenant_id='""" + str(actual_tenant) + """'
  AND i.issuer='http://127.0.0.1:8281/realms/iam-lab'
  AND i.subject='""" + safe_subject + """'
  AND e.lifecycle_status='ACTIVE'
  AND p.permission_key='ACCESS_REQUEST_CREATE';
""")
        matching = fixture.psql(sql).strip()
        print("Diagnostic: exact token actor has local active grant:", matching == "1")
        if matching != "1":
            print("Diagnostic: no exact active grant for this login token in isolated IAM DB.")

    employee_id = args.employee_id or str(uuid.uuid4())
    application_id = args.application_id or str(uuid.uuid4())
    if args.decision_request_id:
        body = json.dumps({"decision": args.decision}).encode()
        target_url = args.api_url.rstrip("/") + "/" + args.decision_request_id + "/decision"
    elif args.quarantine_list:
        body = None
        target_url = urllib.parse.urlunparse((parsed_api.scheme, parsed_api.netloc,
            "/api/v1/provisioning/quarantine", "", "", ""))
    else:
        body = json.dumps({"employeeId": employee_id, "applicationId": application_id,
                           "roleKey": "READER"}).encode()
        target_url = args.api_url
    key = args.idempotency_key or "lab-" + secrets.token_urlsafe(16)
    headers = {"Authorization": "Bearer " + access_token,
               "Content-Type": "application/json",
               "Idempotency-Key": key}

    def post_once():
        request = urllib.request.Request(target_url, data=body, method="GET" if args.quarantine_list else "POST", headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=12) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            # Classify a standard bearer error without logging headers or credentials.
            challenge = error.headers.get("WWW-Authenticate", "")
            if error.code == 403:
                print("Diagnostic: bearer insufficient_scope challenge:",
                      challenge.lower().startswith("bearer ") and
                      "insufficient_scope" in challenge.lower())
            return error.code, None  # discard body to avoid leaking authorization details

    code, data = post_once()
    print("API HTTP status:", code, "(response body not displayed)")
    if code == 401:
        raise RuntimeError("Resource server rejected authentication; inspect issuer/JWKS/audience")
    if args.expect_status is not None and code != args.expect_status:
        raise RuntimeError(f"Expected HTTP {args.expect_status} but observed HTTP {code}")
    if code == 200 and args.quarantine_list:
        if not isinstance(data, list) or any(not isinstance(row, dict) or
                row.get("state") != "QUARANTINED" for row in data):
            raise RuntimeError("Quarantine response contained unexpected data")
        print("PASS: authenticated read-only quarantine list returned; records:", len(data))
    elif code == 200 and args.decision_request_id:
        if not isinstance(data, dict) or data.get("decision") != args.decision or data.get("requestId") != args.decision_request_id:
            raise RuntimeError("Decision response did not match synthetic request/outcome")
        print("PASS: authenticated approval decision persisted (no provisioning verified here)")
    elif code == 201:
        if not isinstance(data, dict) or data.get("status") != "PENDING" or not data.get("id"):
            raise RuntimeError("201 response missing persisted PENDING request identity")
        print("PASS: authorized request persisted as PENDING")
        if args.repeat_on_success:
            retried_code, retried_data = post_once()
            if retried_code != 201 or not isinstance(retried_data, dict) or retried_data.get("id") != data["id"]:
                raise RuntimeError("Idempotent retry failed: HTTP status or request ID changed")
            print("PASS: equivalent retry preserved request ID")
    elif code == 403:
        print("Authorization denied (verify intended grant state).")
    elif code == 404:
        print("Requested target unavailable in authenticated tenant.")
    elif code == 409:
        print("Decision request conflicts with an existing decision." if args.decision_request_id else
              "Existing idempotency key conflicts with changed request terms.")
    if args.expect_status is None:
        print("No expectation asserted; use --expect-status to turn this into verification evidence.")



if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, urllib.error.URLError) as err:
        print("FAIL:", str(err).splitlines()[0], file=sys.stderr)
        sys.exit(1)
