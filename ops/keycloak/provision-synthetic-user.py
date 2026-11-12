#!/usr/bin/env python3
"""Idempotently provision a single synthetic user in LOCAL IAM Keycloak.

Local bootstrap admin login uses an ephemeral admin-cli password grant; the
public iam-lab-cli still exclusively uses Authorization Code + PKCE. For
non-lab automation replace this with a least-privilege service account.
Secrets and tokens are never written to disk, command arguments or logs.
"""
import argparse
import getpass
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "http://127.0.0.1:8281"
REALM = "iam-lab"
USER = "iam-lab-test-admin"


class ProvisionError(Exception):
    pass


def call(path, *, method="GET", token=None, payload=None, form=None):
    if not path.startswith("/"):
        raise ProvisionError("Unsafe API path")
    if form is not None:
        body = urllib.parse.urlencode(form).encode()
        media = "application/x-www-form-urlencoded"
    elif payload is not None:
        body = json.dumps(payload).encode()
        media = "application/json"
    else:
        body, media = None, None
    headers = {"Accept": "application/json"}
    if media:
        headers["Content-Type"] = media
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        req = urllib.request.Request(BASE + path, data=body, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as err:
        # Suppress response bodies and sensitive path parameters.
        raise ProvisionError(f"Keycloak API request failed (HTTP {err.code})") from None
    except urllib.error.URLError:
        raise ProvisionError("IAM lab Keycloak not reachable at 127.0.0.1:8281") from None


def provision():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--tenant-id", default=os.getenv("IAM_LAB_TENANT_ID"), help="Synthetic tenant UUID")
    ap.add_argument("--rotate-password", action="store_true", help="Reset existing user's password explicitly")
    args = ap.parse_args()
    try:
        tenant_id = str(uuid.UUID(args.tenant_id or ""))
    except ValueError:
        ap.error("Set IAM_LAB_TENANT_ID or --tenant-id to a UUID")
    admin_user = os.getenv("IAM_LAB_ADMIN_USERNAME")
    admin_password = os.getenv("IAM_LAB_ADMIN_PASSWORD")
    if not admin_user or not admin_password:
        ap.error("Set IAM_LAB_ADMIN_USERNAME and IAM_LAB_ADMIN_PASSWORD in this terminal")

    # Local development bootstrap only. The public login client never uses password grant.
    tokens = call("/realms/master/protocol/openid-connect/token", method="POST",
                  form={"grant_type": "password", "client_id": "admin-cli",
                        "username": admin_user, "password": admin_password})
    token = tokens["access_token"]
    profile_path = f"/admin/realms/{REALM}/users/profile"
    profile = call(profile_path, token=token)
    attrs = profile.get("attributes", [])
    tenant_attr = next((x for x in attrs if x.get("name") == "tenant_id"), None)
    if tenant_attr is None:
        attrs.append({"name": "tenant_id", "displayName": "Tenant ID",
                      "permissions": {"view": ["admin"], "edit": ["admin"]},
                      "multivalued": False})
        profile["attributes"] = attrs
        call(profile_path, method="PUT", token=token, payload=profile)
        print("PASS: tenant_id profile definition created")
    else:
        perms = tenant_attr.get("permissions", {})
        if "admin" not in perms.get("edit", []):
            raise ProvisionError("tenant_id profile policy is not admin-editable; refusing to weaken policy")
        print("PASS: tenant_id profile definition already present")

    path = f"/admin/realms/{REALM}/users"
    query = urllib.parse.urlencode({"username": USER, "exact": "true"})
    matches = call(path + "?" + query, token=token)
    exact = [x for x in matches if x.get("username") == USER]
    if len(exact) > 1:
        raise ProvisionError("Ambiguous test account")
    created = not exact
    if created:
        call(path, method="POST", token=token,
             payload={"username": USER, "enabled": True, "firstName": "IAM",
                      "lastName": "Test Admin", "requiredActions": [],
                      "attributes": {"tenant_id": [tenant_id]}})
        exact = [x for x in call(path + "?" + query, token=token)
                 if x.get("username") == USER]
        if len(exact) != 1:
            raise ProvisionError("Could not verify new test account")
    user_path = path + "/" + urllib.parse.quote(exact[0]["id"], safe="")
    user = call(user_path, token=token)
    user_attrs = user.get("attributes") or {}
    needs_update = user.get("enabled") is not True or user_attrs.get("tenant_id") != [tenant_id]
    if needs_update:
        user["enabled"] = True
        user_attrs["tenant_id"] = [tenant_id]
        user["attributes"] = user_attrs
        call(user_path, method="PUT", token=token, payload=user)
    print("PASS: synthetic user " + ("created" if created else "already exists"))
    print("PASS: tenant mapping " + ("reconciled" if needs_update else "unchanged"))

    if created or args.rotate_password:
        pw = os.getenv("IAM_LAB_TEST_USER_PASSWORD") or getpass.getpass("Synthetic user password (hidden): ")
        if len(pw) < 12:
            raise ProvisionError("Require at least 12 characters for the lab password")
        call(user_path + "/reset-password", method="PUT", token=token,
             payload={"type": "password", "value": pw, "temporary": False})
        print("PASS: lab password configured without disclosure")
    else:
        print("PASS: password left unchanged; --rotate-password resets it explicitly")
    verified = call(user_path, token=token)
    if verified.get("enabled") is not True or verified.get("attributes", {}).get("tenant_id") != [tenant_id]:
        raise ProvisionError("Readback verification failed")
    print("PASS: user enabled, tenant_id verified; no application permissions granted")


if __name__ == "__main__":
    try:
        provision()
    except (ProvisionError, KeyError) as error:
        print("FAIL:", str(error), file=sys.stderr)
        sys.exit(1)
