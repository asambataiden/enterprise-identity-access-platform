#!/usr/bin/env python3
"""Local-only read-only bearer filter verification against real Keycloak-issued JWTs.

Only GET requests to the fixed IAM quarantine list; never print token or body.
"""
import urllib.error
import urllib.parse
import urllib.request

ALLOWED_HOSTS = frozenset({"127.0.0.1", "localhost"})
READ_PATH = "/api/v1/provisioning/quarantine"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # A redirect may leak an Authorization header to a different origin.
        # The lab never follows redirects; responses outside 200/401/403 fail.
        return None


def corrupted_signature(token: str) -> str:
    parts = token.split(".")
    if len(parts) != 3 or not all(parts):
        raise ValueError("Expected a three-part signed JWT access token")
    # Base64url signatures start with [A-Za-z0-9_-]. Flip a high-order value
    # to avoid altering only unused trailing bits of the encoding.
    signature = parts[2]
    changed = "A" if signature[0] != "A" else "B"
    return parts[0] + "." + parts[1] + "." + changed + signature[1:]


def validated_endpoint(api_url: str) -> str:
    p = urllib.parse.urlsplit(api_url)
    try:
        port = p.port
    except ValueError:
        raise ValueError("Invalid API URL port") from None
    if (
        p.scheme != "http"
        or p.hostname not in ALLOWED_HOSTS
        or port != 8082
        or p.username or p.password or p.query or p.fragment
        or p.path not in ("", "/", "/api/v1/access-requests", READ_PATH)
    ):
        raise ValueError("Only the fixed loopback IAM API port 8082 is allowed")
    # Build a hard-coded host/port rather than trusting arbitrary netloc data.
    return f"http://{p.hostname}:8082{READ_PATH}"


def readonly_status(endpoint: str, token: str | None) -> int:
    headers = {"Accept": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(endpoint, method="GET", headers=headers)
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(req, timeout=12) as response:
            return response.status
    except urllib.error.HTTPError as exc:
        # Do not log URL, Authorization/WWW-Authenticate or response body.
        return exc.code


def verify_real_bearer_boundary(api_url: str, access_token: str) -> None:
    endpoint = validated_endpoint(api_url)
    bad_token = corrupted_signature(access_token)
    anonymous = readonly_status(endpoint, None)
    tampered = readonly_status(endpoint, bad_token)
    genuine = readonly_status(endpoint, access_token)
    if anonymous != 401:
        raise RuntimeError(f"Anonymous request expected HTTP 401; got {anonymous}")
    if tampered != 401:
        raise RuntimeError(f"Tampered signed token expected HTTP 401; got {tampered}")
    if genuine not in (200, 403):
        raise RuntimeError(f"Real signed token was not authenticated; got HTTP {genuine}")
    print("PASS: anonymous quarantine GET -> HTTP 401")
    print("PASS: tampered JWT signature quarantine GET -> HTTP 401")
    print(f"PASS: genuine Keycloak access token quarantine GET -> HTTP {genuine}")
    if genuine == 403:
        print("LIMIT: authentication succeeded; route/local authorization not proven")
    else:
        print("PASS: authenticated route authorized; inspect tenant fixture separately")
    print("No bearer values or HTTP response bodies were displayed or saved")
