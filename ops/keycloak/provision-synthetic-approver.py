#!/usr/bin/env python3
"""Manage a SECOND synthetic approver for the disposable localhost IAM lab only.

Default provisions the synthetic Keycloak user + its local employee binding,
without granting permission. --grant / --revoke operate exclusively on the
approver's ACCESS_REQUEST_DECIDE row. Never modifies the existing requester.
"""
import argparse
import getpass
import importlib.util
import os
from pathlib import Path
import sys
import urllib.parse
import uuid

HERE = Path(__file__).resolve().parent
USER = "iam-lab-test-approver"
ISSUER = "http://127.0.0.1:8281/realms/iam-lab"

def load(filename, name):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

kc = load("provision-synthetic-user.py", "iam_approver_kc")
fixture = load("seed-authorized-iam-fixture.py", "iam_approver_db")

def require_tenant(raw):
    try:
        return uuid.UUID(raw or "")
    except (ValueError, TypeError):
        raise RuntimeError("Set IAM_LAB_TENANT_ID to the synthetic tenant UUID") from None

def admin_token():
    user = os.environ.get("IAM_LAB_ADMIN_USERNAME")
    pw = os.environ.get("IAM_LAB_ADMIN_PASSWORD")
    if not user or not pw:
        raise RuntimeError("Set local IAM_LAB_ADMIN_USERNAME and IAM_LAB_ADMIN_PASSWORD securely")
    return kc.call("/realms/master/protocol/openid-connect/token", method="POST",
                   form={"grant_type":"password","client_id":"admin-cli",
                         "username":user,"password":pw})["access_token"]

def provision_user(token, tenant, rotate, allow_create=True):
    root="/admin/realms/iam-lab/users"
    query=urllib.parse.urlencode({"username":USER,"exact":"true"})
    matches=[v for v in kc.call(root+"?"+query,token=token) if v.get("username")==USER]
    if len(matches)>1:
        raise RuntimeError("Duplicate synthetic approver account; refusing changes")
    created = not matches
    if created and not allow_create:
        raise RuntimeError("Synthetic approver account not present; refusing to create it during revoke")
    if created:
        kc.call(root, method="POST", token=token, payload={
            "username":USER, "enabled":True, "firstName":"IAM", "lastName":"Lab Approver",
            "requiredActions":[], "attributes":{"tenant_id":[str(tenant)]}})
        matches=[v for v in kc.call(root+"?"+query,token=token) if v.get("username")==USER]
        if len(matches)!=1:
            raise RuntimeError("New synthetic approver not readable")
    uid=matches[0]["id"]
    path=root+"/"+urllib.parse.quote(uid,safe="")
    record=kc.call(path,token=token)
    if record.get("enabled") is not True:
        raise RuntimeError("Existing approver disabled; refusing to silently enable")
    if record.get("attributes",{}).get("tenant_id") != [str(tenant)]:
        raise RuntimeError("Approver has conflicting tenant mapping; refusing reassignment")
    if created or rotate:
        pw=os.environ.get("IAM_LAB_APPROVER_PASSWORD") or getpass.getpass("New synthetic approver password (hidden): ")
        if len(pw)<12:
            raise RuntimeError("Approver lab password must contain at least 12 characters")
        kc.call(path+"/reset-password", method="PUT", token=token,
                payload={"type":"password","value":pw,"temporary":False})
    verified=kc.call(path,token=token)
    if verified.get("enabled") is not True or verified.get("attributes",{}).get("tenant_id") != [str(tenant)]:
        raise RuntimeError("Approver account readback failed")
    print("PASS: separate synthetic approver account verified; password not shown")
    return uid

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    options=ap.add_mutually_exclusive_group()
    options.add_argument("--grant",action="store_true",help="Grant only the synthetic approver's DECIDE permission")
    options.add_argument("--revoke",action="store_true",help="Revoke only the synthetic approver's DECIDE permission")
    ap.add_argument("--rotate-password", action="store_true",help="Rotate only the approver's password")
    args=ap.parse_args()
    tenant=require_tenant(os.getenv("IAM_LAB_TENANT_ID"))
    if args.revoke and args.rotate_password:
        ap.error("Password rotation and revoke must be separate operations")
    actor=uuid.uuid5(tenant,"iam-lab-test-approver:employee")
    original_actor=uuid.uuid5(tenant,"iam-lab-test-admin:employee")
    if actor == original_actor:
        raise RuntimeError("Approver and requester must not be the same employee")
    # Validate the IAM-only DB and existing tenant before changing Keycloak.
    # psql() validates container name AND 127.0.0.1:55432 binding.
    exists=fixture.psql(f"""SELECT count(*) FROM tenants WHERE id='{tenant}';""")
    if exists != "1":
        raise RuntimeError("Synthetic tenant fixture missing; refusing to modify Keycloak")
    token=admin_token()
    subject=provision_user(token,tenant,args.rotate_password,allow_create=not args.revoke)
    mapping=fixture.psql(f"""SELECT count(*) FROM employee_identities
WHERE tenant_id='{tenant}' AND issuer='{ISSUER}' AND subject='{subject}';""")
    if mapping not in ("0","1"):
        raise RuntimeError("Unexpected existing identity mapping")
    if mapping == "1":
        owned=fixture.psql(f"""SELECT count(*) FROM employee_identities
WHERE tenant_id='{tenant}' AND employee_id='{actor}' AND issuer='{ISSUER}' AND subject='{subject}';""")
        if owned != "1":
            raise RuntimeError("Approver Keycloak principal belongs to another employee; refusing reassignment")

    sql=f"""BEGIN;
INSERT INTO employees(tenant_id,id,lifecycle_status)
VALUES ('{tenant}','{actor}','ACTIVE') ON CONFLICT (tenant_id,id) DO NOTHING;
INSERT INTO employee_identities(tenant_id,employee_id,issuer,subject)
VALUES ('{tenant}','{actor}','{ISSUER}','{subject}') ON CONFLICT DO NOTHING;
"""
    if args.grant:
        sql+=f"""INSERT INTO employee_permissions(tenant_id,employee_id,permission_key)
VALUES ('{tenant}','{actor}','ACCESS_REQUEST_DECIDE') ON CONFLICT DO NOTHING;
"""
    elif args.revoke:
        sql+=f"""DELETE FROM employee_permissions
WHERE tenant_id='{tenant}' AND employee_id='{actor}'
  AND permission_key='ACCESS_REQUEST_DECIDE';
"""
    sql+="COMMIT;"
    fixture.psql(sql)
    verified=fixture.psql(f"""SELECT count(*) FROM employees e JOIN employee_identities i
 ON e.tenant_id=i.tenant_id AND e.id=i.employee_id
WHERE e.tenant_id='{tenant}' AND e.id='{actor}' AND e.lifecycle_status='ACTIVE'
 AND i.issuer='{ISSUER}' AND i.subject='{subject}';""")
    if verified!="1":
        raise RuntimeError("Synthetic approver local identity readback failed")
    grant=fixture.psql(f"""SELECT count(*) FROM employee_permissions
WHERE tenant_id='{tenant}' AND employee_id='{actor}' AND permission_key='ACCESS_REQUEST_DECIDE';""")
    if (args.grant and grant!="1") or (args.revoke and grant!="0"):
        raise RuntimeError("Approver permission readback failed")
    # Guard against accidental unintended restoration of the original actor grant.
    requester_create=fixture.psql(f"""SELECT count(*) FROM employee_permissions
WHERE tenant_id='{tenant}' AND employee_id='{original_actor}'
  AND permission_key='ACCESS_REQUEST_CREATE';""")
    if requester_create!="0":
        raise RuntimeError("Requester CREATE permission is unexpectedly present; inspect lab state (this script did not change it)")
    print("PASS: approver bound to a distinct active tenant employee")
    print("PASS: approver DECIDE grant " + ("present" if grant=="1" else "absent"))
    print("PASS: original requester's CREATE grant remains revoked")
    print("No production identities, other clients, or unrelated local permissions modified")

if __name__=="__main__":
    try:
        main()
    except (RuntimeError, kc.ProvisionError, KeyError, OSError, ValueError) as err:
        print("FAIL:",str(err).splitlines()[0],file=sys.stderr)
        sys.exit(1)
