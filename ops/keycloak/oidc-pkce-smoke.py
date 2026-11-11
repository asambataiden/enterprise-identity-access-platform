#!/usr/bin/env python3
"""Local-only OIDC authorization-code + PKCE smoke client.

No password grant, client secret, token files, token stdout or JWT-as-trust.
Do not use outside the isolated iam-lab developer environment.
"""
import argparse
import base64
import hashlib
import http.server
import json
import secrets
import sys
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
    args = parser.parse_args()
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

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            params = urllib.parse.parse_qs(parsed.query)
            if parsed.path != "/callback" or params.get("state") != [state]:
                self.send_error(400, "Unexpected callback/state")
                return
            Callback.result = params
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"Local login callback received. Return to your terminal.")

        def log_message(self, format, *args):
            pass  # never log authorization codes or query strings

    server = http.server.HTTPServer(("127.0.0.1", 8765), Callback)
    server.timeout = 180
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
        server.handle_request()
    finally:
        server.server_close()
    result = Callback.result
    if not result or "code" not in result:
        raise RuntimeError("Login callback missing, timed out, or denied")
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

    employee_id = args.employee_id or str(uuid.uuid4())
    application_id = args.application_id or str(uuid.uuid4())
    body = json.dumps({"employeeId": employee_id, "applicationId": application_id,
                       "roleKey": "READER"}).encode()
    request = urllib.request.Request(args.api_url, data=body, method="POST",
                                     headers={"Authorization": "Bearer " + access_token,
                                              "Content-Type": "application/json",
                                              "Idempotency-Key": "lab-" + secrets.token_urlsafe(16)})
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            print("API HTTP status:", response.status)
    except urllib.error.HTTPError as error:
        print("API HTTP status:", error.code, "(response body deliberately hidden)")
        if error.code == 401:
            raise RuntimeError("Resource server rejected authentication; inspect issuer/JWKS/audience") from None
        if error.code == 403:
            print("Authorization denied: expected until explicit identity + permission mapping exists.")
    print("Do not interpret this helper as a completed positive access-request test.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, KeyError, urllib.error.URLError) as err:
        print("FAIL:", str(err).splitlines()[0], file=sys.stderr)
        sys.exit(1)
