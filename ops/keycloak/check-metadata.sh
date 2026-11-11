#!/usr/bin/env bash
# Read-only Keycloak OIDC discovery and JWKS check. No tokens printed or stored.
set -Eeuo pipefail
url="http://127.0.0.1:8081/realms/iam-lab"
command -v curl >/dev/null || { echo 'FAIL: curl missing' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'FAIL: python3 missing' >&2; exit 1; }
metadata="$(curl --fail --silent --show-error --max-time 10 "$url/.well-known/openid-configuration")"
jwks="$(curl --fail --silent --show-error --max-time 10 "$url/protocol/openid-connect/certs")"
printf '%s\n' "$metadata" | python3 -c '
import json,sys
d=json.load(sys.stdin)
expected="http://127.0.0.1:8081/realms/iam-lab"
assert d.get("issuer")==expected, f"unexpected issuer: {d.get(chr(105)+chr(115)+chr(115)+chr(117)+chr(101)+chr(114))}"
assert d.get("jwks_uri")==expected+"/protocol/openid-connect/certs"
print("PASS: local issuer and JWKS endpoint match application configuration")
'
printf '%s\n' "$jwks" | python3 -c '
import json,sys
keys=json.load(sys.stdin).get("keys",[])
assert any(k.get("kty")=="RSA" and k.get("use","sig")=="sig" for k in keys), "no RSA signing key advertised"
print("PASS: JWKS advertises an RSA signing key; token interoperability NOT yet verified")
'
