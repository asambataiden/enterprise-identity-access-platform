#!/usr/bin/env python3
"""Verify the local Keycloak OIDC discovery document and signing-key endpoint.

This is a readiness/trust-metadata smoke check, not an access-token validation test.
It uses only Python's standard library and never requests credentials or tokens.
"""
from __future__ import annotations

import json
import os
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

BASE_URL = os.environ.get("IAM_KEYCLOAK_BASE_URL", "http://127.0.0.1:8281").rstrip("/")
REALM = os.environ.get("IAM_KEYCLOAK_REALM", "iam-lab")
EXPECTED_ISSUER = os.environ.get(
    "IAM_JWT_ISSUER_URI", f"{BASE_URL}/realms/{REALM}"
)
TIMEOUT_SECONDS = 3
ATTEMPTS = 30
RETRY_DELAY_SECONDS = 2


def fetch_json(url: str) -> dict:
    request = Request(url, headers={"Accept": "application/json"})
    with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        if response.status != 200:
            raise RuntimeError(f"Unexpected HTTP status {response.status} from {url}")
        payload = json.load(response)
    if not isinstance(payload, dict):
        raise RuntimeError(f"Expected a JSON object from {url}")
    return payload


def verify() -> None:
    base = urlparse(BASE_URL)
    issuer = urlparse(EXPECTED_ISSUER)
    if base.scheme not in {"http", "https"} or not base.netloc:
        raise RuntimeError("IAM_KEYCLOAK_BASE_URL must be an absolute HTTP(S) URL")
    if (issuer.scheme, issuer.netloc) != (base.scheme, base.netloc):
        raise RuntimeError("Expected issuer must use the configured Keycloak origin")

    discovery_url = f"{EXPECTED_ISSUER}/.well-known/openid-configuration"
    metadata = fetch_json(discovery_url)
    if metadata.get("issuer") != EXPECTED_ISSUER:
        raise RuntimeError(
            f"Issuer mismatch: expected {EXPECTED_ISSUER!r}, "
            f"received {metadata.get('issuer')!r}"
        )

    jwks_uri = metadata.get("jwks_uri")
    parsed_jwks = urlparse(jwks_uri or "")
    if (parsed_jwks.scheme, parsed_jwks.netloc) != (base.scheme, base.netloc):
        raise RuntimeError("JWKS endpoint must remain on the configured Keycloak origin")
    if not parsed_jwks.path.startswith(issuer.path.rstrip("/") + "/"):
        raise RuntimeError("JWKS endpoint is outside the configured realm path")

    jwks = fetch_json(jwks_uri)
    keys = jwks.get("keys")
    if not isinstance(keys, list) or not keys:
        raise RuntimeError("JWKS contains no signing keys")
    signing_keys = [
        key for key in keys
        if isinstance(key, dict)
        and key.get("kid")
        and key.get("kty")
        and key.get("use", "sig") == "sig"
    ]
    if not signing_keys:
        raise RuntimeError("JWKS contains no usable signing key metadata")

    print("PASS: Keycloak OIDC discovery is reachable")
    print(f"PASS: issuer matches {EXPECTED_ISSUER}")
    print(f"PASS: JWKS endpoint is same-origin and realm-scoped")
    print(f"PASS: {len(signing_keys)} signing key(s) expose kid and kty metadata")
    print("NOTE: no user authentication, token signature, audience or authorization was tested")


def main() -> int:
    for attempt in range(1, ATTEMPTS + 1):
        try:
            verify()
            return 0
        except (HTTPError, URLError, TimeoutError, OSError, ValueError, RuntimeError) as exc:
            if attempt == ATTEMPTS:
                print(f"FAIL: Keycloak runtime smoke check failed: {exc}", file=sys.stderr)
                return 1
            time.sleep(RETRY_DELAY_SECONDS)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
