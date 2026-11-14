#!/usr/bin/env python3
"""Read-only HTTP bearer-token trust-boundary probes for the local IAM lab.

This module NEVER prints, persists or returns access tokens to the user.
The authentic token must come from the existing Authorization Code + PKCE flow.
"""
import urllib.error
import urllib.parse
import urllib.request


def corrupted_signature(token: str) -> str:
    parts = token.split(".")
    if len(parts) != 3 or not all(parts):
        raise ValueError("Expected a three-part signed JWT access token")
    signature = parts[2]
    # Alter a high-order base64url character, not trailing padding bits.
    substitute = "A" if signature[0] != "A" else "B"
    return parts[0] + "." + parts[1] + "." + substitute + signature[1:]


def readonly_status(url: str, token: str | None) -> int:
    headers = {"Accept": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, method="GET", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=12) as response:
            return response.status
    except urllib.error.HTTPError as exc:
        # Do not echo WWW-Authenticate headers, URL query strings or response bodies.
        return exc.code


def verify_real_bearer_boundary(api_url: str, access_token: str) -> None:
    parsed = urllib.parse.urlparse(api_url)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost"):
        raise ValueError("Only local HTTP IAM endpoints are permitted")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("IAM API URL may not contain userinfo, query or fragment")
    endpoint = urllib.parse.urlunparse(
        (parsed.scheme, parsed.netloc, "/api/v1/provisioning/quarantine", "", "", "")
    )

    anonymous = readonly_status(endpoint, None)
    modified = readonly_status(endpoint, corrupted_signature(access_token))
    authentic = readonly_status(endpoint, access_token)

    if anonymous != 401:
        raise RuntimeError(f"Anonymous read-only probe expected HTTP 401; observed HTTP {anonymous}")
    if modified != 401:
        raise RuntimeError(f"Corrupted-signature probe expected HTTP 401; observed HTTP {modified}")
    # A legitimate authentication can yield 403 when this synthetic user lacks
    # both the provisioning:review scope and local quarantine reviewer permission.
    if authentic not in (200, 403):
        raise RuntimeError(
            f"Valid Keycloak access token was not recognized by API; observed HTTP {authentic}"
        )
    print("PASS: real HTTP filter chain rejects anonymous request with 401")
    print("PASS: real HTTP filter chain rejects corrupted JWT signature with 401")
    print(f"PASS: genuine Keycloak access token reached authorization (HTTP {authentic})")
    print("NOTE: 403 means authenticated but forbidden; this does NOT prove local permission")
    print("No access token, callback code or private key was displayed, saved or transmitted elsewhere")
