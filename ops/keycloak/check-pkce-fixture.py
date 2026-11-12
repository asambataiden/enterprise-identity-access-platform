#!/usr/bin/env python3
"""Static assertions for synthetic, local-only OIDC fixture; no Docker required."""
import json
from pathlib import Path

root = Path(__file__).resolve().parent
data = json.loads((root / "realm" / "iam-lab-realm.json").read_text())
assert data["realm"] == "iam-lab"
assert data.get("registrationAllowed") is False
assert not data.get("users"), "Do not commit synthetic user credentials"
clients = [c for c in data["clients"] if c["clientId"] == "iam-lab-cli"]
assert len(clients) == 1
client = clients[0]
assert client["publicClient"] and client["standardFlowEnabled"]
assert not client["implicitFlowEnabled"] and not client["directAccessGrantsEnabled"]
assert client["attributes"]["pkce.code.challenge.method"] == "S256"
assert client["redirectUris"] == ["http://127.0.0.1:8765/callback"]
assert client.get("webOrigins") == []
assert "access:request" in client["optionalClientScopes"]
assert "access:decide" in client["optionalClientScopes"]
mappers = {m["protocolMapper"]: m for m in client["protocolMappers"]}
assert mappers["oidc-audience-mapper"]["config"]["included.custom.audience"] == "iam-governance-api"
assert mappers["oidc-usermodel-attribute-mapper"]["config"]["claim.name"] == "tenant_id"
assert mappers["oidc-sub-mapper"]["config"]["access.token.claim"] == "true"
scopes = [s for s in data["clientScopes"] if s["name"] == "access:request"]
assert len(scopes) == 1 and scopes[0]["attributes"]["include.in.token.scope"] == "true"
decide_scopes = [s for s in data["clientScopes"] if s["name"] == "access:decide"]
assert len(decide_scopes) == 1 and decide_scopes[0]["attributes"]["include.in.token.scope"] == "true"
print("PASS: no-password-grant PKCE client, strict callback, audience, tenant mapping, optional scope")
