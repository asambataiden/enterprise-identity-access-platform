#!/usr/bin/env python3
"""Idempotently reconcile ONLY the optional provisioning:review scope on local IAM Keycloak.

Requires the already configured local lab bootstrap admin variables. Does not
create accounts, change credentials, modify access:request, or touch other clients.
"""
import importlib.util
import json
import os
from pathlib import Path
import sys
import urllib.parse

SRC = Path(__file__).with_name("provision-synthetic-user.py")
spec = importlib.util.spec_from_file_location("iam_local_kc", SRC)
kc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kc)

SCOPE = "provisioning:review"
CLIENT = "iam-lab-cli"

def admin_token():
    username = os.getenv("IAM_LAB_ADMIN_USERNAME")
    password = os.getenv("IAM_LAB_ADMIN_PASSWORD")
    if not username or not password:
        raise RuntimeError("Configure IAM_LAB_ADMIN_USERNAME and IAM_LAB_ADMIN_PASSWORD in this terminal")
    return kc.call("/realms/master/protocol/openid-connect/token", method="POST",
                   form={"grant_type":"password", "client_id":"admin-cli",
                         "username":username, "password":password})["access_token"]

def main():
    token = admin_token()
    root = "/admin/realms/iam-lab"
    scopes = kc.call(root + "/client-scopes", token=token)
    matches = [s for s in scopes if s.get("name") == SCOPE]
    if len(matches) > 1:
        raise RuntimeError("Ambiguous quarantine review client scope; refusing modification")
    if not matches:
        kc.call(root + "/client-scopes", method="POST", token=token,
                payload={"name":SCOPE,"protocol":"openid-connect",
                         "attributes":{"include.in.token.scope":"true",
                                       "display.on.consent.screen":"false"}})
    scopes = kc.call(root + "/client-scopes", token=token)
    matches = [s for s in scopes if s.get("name") == SCOPE]
    if len(matches) != 1:
        raise RuntimeError("Review scope not found after reconciliation")
    scope = matches[0]
    if scope.get("protocol") != "openid-connect" or scope.get("attributes",{}).get("include.in.token.scope") != "true":
        raise RuntimeError("Existing review scope has incompatible configuration; refusing to overwrite")
    query = urllib.parse.urlencode({"clientId":CLIENT})
    clients = [c for c in kc.call(root + "/clients?" + query, token=token) if c.get("clientId") == CLIENT]
    if len(clients) != 1:
        raise RuntimeError("Exact synthetic PKCE client not found")
    client_id = clients[0]["id"]
    path = root + "/clients/" + urllib.parse.quote(client_id, safe="")
    client = kc.call(path, token=token)
    if not client.get("publicClient") or not client.get("standardFlowEnabled") or client.get("directAccessGrantsEnabled"):
        raise RuntimeError("Unsafe or unexpected PKCE client configuration; refusing change")
    optional_path = path + "/optional-client-scopes"
    assigned = kc.call(optional_path, token=token)
    if SCOPE not in [s.get("name") for s in assigned]:
        kc.call(optional_path + "/" + urllib.parse.quote(scope["id"],safe=""),
                method="PUT", token=token)
    assigned = kc.call(optional_path, token=token)
    if sum(s.get("name") == SCOPE for s in assigned) != 1:
        raise RuntimeError("Review scope assignment readback failed")
    print("PASS: optional provisioning:review scope configured on isolated iam-lab-cli")
    print("PASS: verified readback; no users, passwords, default scopes or other clients changed")

if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, kc.ProvisionError, KeyError, OSError, ValueError) as error:
        print("FAIL:", str(error).splitlines()[0], file=sys.stderr)
        sys.exit(1)
