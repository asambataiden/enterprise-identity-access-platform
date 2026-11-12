#!/usr/bin/env python3
"""Reconcile the immutable OIDC subject mapper for ONLY the local IAM PKCE client.

No container reset or password rotation. Requires existing local admin credentials
in IAM_LAB_ADMIN_USERNAME and IAM_LAB_ADMIN_PASSWORD; never prints tokens.
"""
import importlib.util
import os
from pathlib import Path
import sys
import urllib.parse

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("iam_lab_provision", HERE / "provision-synthetic-user.py")
provision = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provision)
call = provision.call

REALM = "iam-lab"
CLIENT = "iam-lab-cli"
MAPPER_NAME = "iam-oidc-subject"
MAPPER_TYPE = "oidc-sub-mapper"
EXPECTED = {"access.token.claim": "true", "lightweight.claim": "false",
            "introspection.token.claim": "true"}


def reconcile():
    username, password = os.getenv("IAM_LAB_ADMIN_USERNAME"), os.getenv("IAM_LAB_ADMIN_PASSWORD")
    if not username or not password:
        raise provision.ProvisionError("Set IAM_LAB_ADMIN_USERNAME/PASSWORD in this terminal")
    auth = call("/realms/master/protocol/openid-connect/token", method="POST",
                form={"grant_type": "password", "client_id": "admin-cli",
                      "username": username, "password": password})["access_token"]
    base = f"/admin/realms/{REALM}/clients"
    clients = call(base + "?" + urllib.parse.urlencode({"clientId": CLIENT}), token=auth)
    matched = [c for c in clients if c.get("clientId") == CLIENT]
    if len(matched) != 1 or matched[0].get("enabled") is not True:
        raise provision.ProvisionError("Expected exactly one enabled local PKCE client")
    client = matched[0]
    if not client.get("publicClient") or not client.get("standardFlowEnabled") or client.get("directAccessGrantsEnabled"):
        raise provision.ProvisionError("Client authentication/PKCE policy unexpected; refusing changes")
    path = base + "/" + urllib.parse.quote(client["id"], safe="") + "/protocol-mappers/models"
    current = call(path, token=auth)
    existing = [m for m in current if m.get("name") == MAPPER_NAME or m.get("protocolMapper") == MAPPER_TYPE]
    if len(existing) > 1:
        raise provision.ProvisionError("Multiple subject mappers: refusing ambiguous configuration")
    desired = {"name": MAPPER_NAME, "protocol": "openid-connect",
               "protocolMapper": MAPPER_TYPE, "consentRequired": False,
               "config": EXPECTED}
    if not existing:
        call(path, method="POST", token=auth, payload=desired)
        print("PASS: OIDC subject mapper installed on local PKCE client")
    else:
        present = existing[0]
        if present.get("protocolMapper") != MAPPER_TYPE:
            raise provision.ProvisionError("Mapper name conflict; refusing replacement")
        config = dict(present.get("config") or {})
        if any(config.get(k) != v for k, v in EXPECTED.items()):
            config.update(EXPECTED)
            # update only this mapper; retain unrelated mapper attributes
            present["config"] = config
            mapper_id = urllib.parse.quote(present["id"], safe="")
            call(path + "/" + mapper_id, method="PUT", token=auth, payload=present)
            print("PASS: OIDC subject mapper settings reconciled")
        else:
            print("PASS: OIDC subject mapper already configured")
    verify = [m for m in call(path, token=auth) if m.get("protocolMapper") == MAPPER_TYPE]
    if len(verify) != 1 or any(verify[0].get("config", {}).get(k) != v for k, v in EXPECTED.items()):
        raise provision.ProvisionError("Subject mapper readback verification failed")
    print("PASS: access-token sub mapper verified; no users/passwords/other clients modified")


if __name__ == "__main__":
    try:
        reconcile()
    except (provision.ProvisionError, KeyError, ValueError) as err:
        print("FAIL:", str(err).splitlines()[0], file=sys.stderr)
        sys.exit(1)
