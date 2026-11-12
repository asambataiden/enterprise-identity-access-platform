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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-url", default="http://127.0.0.1:8082/api/v1/access-requests",
                        help="Loopback IAM API URL for safe negative smoke test")
    parser.add_argument("--employee-id", help="Synthetic employee UUID (requires authorized local fixture)")
    parser.add_argument("--application-id", help="Synthetic application UUID (requires authorized local fixture)")
    parser.add_argument("--idempotency-key", help="Stable test-only key to verify retries/conflicts")
    parser.add_argument("--expect-status", type=int, choices=[201, 403, 404, 409],
                        help="Fail if the real API status differs")
    parser.add_argument("--diagnose-local-binding", action="store_true",
                        help="Read-only fixture DB check using token claims (diagnostic, not JWT verification)")
    parser.add_argument("--repeat-on-success", action="store_true",
                        help="Repeat authorized POST once and assert same persisted request ID")
    args = parser.parse_args()
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
        "scope": "openid access:request",
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
    print("  access:request scope present:", "access:request" in scopes)
    print("  tenant_id present:", bool(claims.get("tenant_id")))
    print("Access token intentionally not displayed or saved.")

    if args.diagnose_local_binding:
        # This is a controlled local diagnostic, not a production auth check.
        # JWT claims are NOT signature-verified by Python. The Java API verifies JWTs.
        fixture_path = Path(__file__).resolve().with_name("seed-authorized-iam-fixture.py")
        spec = importlib.util.spec_from_file_location("iam_lab_fixture", fixture_path)
        fixture = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fixture)
        expected_tenant = uuid.UUID("8230545d-3560-41e6-af0b-b1ef50778606")
        try:
            actual_tenant = uuid.UUID(str(claims.get("tenant_id", "")))
            subject = uuid.UUID(str(claims.get("sub", "")))
        except (ValueError, TypeError):
            raise RuntimeError("Token tenant/subject is not a valid UUID") from None
        issuer = claims.get("iss")
        if issuer != ISSUER:
            raise RuntimeError("Token issuer differs from local expected issuer")
        print("Diagnostic: token tenant matches fixture:", actual_tenant == expected_tenant)
        sql = ("""
SELECT count(*)
FROM employee_identities i
JOIN employees e ON e.tenant_id=i.tenant_id AND e.id=i.employee_id
JOIN employee_permissions p
  ON p.tenant_id=e.tenant_id AND p.employee_id=e.id
WHERE i.tenant_id='""" + str(actual_tenant) + """'
  AND i.issuer='http://127.0.0.1:8281/realms/iam-lab'
  AND i.subject='""" + str(subject) + """'
  AND e.lifecycle_status='ACTIVE'
  AND p.permission_key='ACCESS_REQUEST_CREATE';
""")
        matching = fixture.psql(sql).strip()
        print("Diagnostic: exact token actor has local active grant:", matching == "1")
        if matching != "1":
            print("Diagnostic: no exact active grant for this login token in isolated IAM DB.")

    employee_id = args.employee_id or str(uuid.uuid4())
    application_id = args.application_id or str(uuid.uuid4())
    body = json.dumps({"employeeId": employee_id, "applicationId": application_id,
                       "roleKey": "READER"}).encode()
    key = args.idempotency_key or "lab-" + secrets.token_urlsafe(16)
    headers = {"Authorization": "Bearer " + access_token,
               "Content-Type": "application/json",
               "Idempotency-Key": key}

    def post_once():
        request = urllib.request.Request(args.api_url, data=body, method="POST", headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=12) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, None  # discard body to avoid leaking authorization details

    code, data = post_once()
    print("API HTTP status:", code, "(response body not displayed)")
    if code == 401:
        raise RuntimeError("Resource server rejected authentication; inspect issuer/JWKS/audience")
    if args.expect_status is not None and code != args.expect_status:
        raise RuntimeError(f"Expected HTTP {args.expect_status} but observed HTTP {code}")
    if code == 201:
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
        print("Existing idempotency key conflicts with changed request terms.")
    if args.expect_status is None:
        print("No expectation asserted; use --expect-status to turn this into verification evidence.")



if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, urllib.error.URLError) as err:
        print("FAIL:", str(err).splitlines()[0], file=sys.stderr)
        sys.exit(1)
