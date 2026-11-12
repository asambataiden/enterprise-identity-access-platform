#!/usr/bin/env python3
"""Local IAM-only synthetic SQL fixture; never use on production databases.

Resolves the real Keycloak synthetic subject with Admin API, then binds it to a
deterministic local employee and explicit ACCESS_REQUEST_CREATE permission.
No bearer token, password or SQL connection secret is printed or persisted.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[2]
KEYCLOAK = ROOT / "ops/keycloak/provision-synthetic-user.py"
TENANT_ENV = "IAM_LAB_TENANT_ID"
ISSUER = "http://127.0.0.1:8281/realms/iam-lab"
DB_CONTAINER = "keycloak-iam-lab-db-1"
DB_COMPOSE = ROOT / "ops/keycloak/postgres.compose.yaml"


def kc_module():
    spec = importlib.util.spec_from_file_location("local_keycloak_provisioner", KEYCLOAK)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def psql(sql):
    # Prevent accidental execution in any database other than the lab container
    info = subprocess.run(["docker", "inspect", DB_CONTAINER, "--format", "{{json .NetworkSettings.Ports}}"],
                          capture_output=True, text=True, check=True)
    ports = json.loads(info.stdout)
    bindings = ports.get("5432/tcp") or []
    if len(bindings) != 1 or bindings[0].get("HostIp") != "127.0.0.1" or bindings[0].get("HostPort") != "55432":
        raise RuntimeError("Refusing SQL: expected IAM-only DB container on 127.0.0.1:55432")
    run = subprocess.run(["docker", "compose", "-f", str(DB_COMPOSE), "exec", "-T",
                          "iam-lab-db", "psql", "-X", "-q", "-t", "-A", "-v", "ON_ERROR_STOP=1",
                          "-U", "iam_app", "-d", "iamdb"],
                         input=sql, text=True, capture_output=True)
    if run.returncode:
        raise RuntimeError("IAM-only fixture SQL failed; inspect lab database health (details suppressed)")
    return run.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revoke", action="store_true", help="Remove only this lab actor's ACCESS_REQUEST_CREATE grant")
    args = parser.parse_args()
    try:
        tenant = uuid.UUID(os.environ.get(TENANT_ENV, ""))
    except ValueError:
        parser.error("Set IAM_LAB_TENANT_ID to the synthetic tenant UUID")
    if not os.environ.get("IAM_LAB_ADMIN_USERNAME") or not os.environ.get("IAM_LAB_ADMIN_PASSWORD"):
        parser.error("Set IAM_LAB_ADMIN_USERNAME and IAM_LAB_ADMIN_PASSWORD securely in this terminal")
    kc = kc_module()
    auth = kc.call("/realms/master/protocol/openid-connect/token", method="POST",
                   form={"grant_type": "password", "client_id": "admin-cli",
                         "username": os.environ["IAM_LAB_ADMIN_USERNAME"],
                         "password": os.environ["IAM_LAB_ADMIN_PASSWORD"]})["access_token"]
    path = "/admin/realms/iam-lab/users?" + urllib.parse.urlencode(
        {"username": "iam-lab-test-admin", "exact": "true"})
    matched = [u for u in kc.call(path, token=auth) if u.get("username") == "iam-lab-test-admin"]
    if len(matched) != 1 or matched[0].get("enabled") is not True:
        raise RuntimeError("Synthetic test user absent or disabled; run provisioning first")
    subject = uuid.UUID(matched[0]["id"])
    actor = uuid.uuid5(tenant, "iam-lab-test-admin:employee")
    target = uuid.uuid5(tenant, "iam-lab-test-employee:target")
    application = uuid.uuid5(tenant, "iam-lab-test-app:application")
    foreign_tenant = uuid.uuid5(tenant, "iam-lab-foreign-tenant")
    foreign_employee = uuid.uuid5(tenant, "iam-lab-foreign-employee")

    if args.revoke:
        sql = f"""BEGIN;
DELETE FROM employee_permissions
WHERE tenant_id = '{tenant}' AND employee_id = '{actor}'
  AND permission_key = 'ACCESS_REQUEST_CREATE';
COMMIT;
"""
    else:
        sql = f"""BEGIN;
INSERT INTO tenants(id,name)
VALUES ('{tenant}', 'iam-lab-{tenant}') ON CONFLICT(id) DO NOTHING;
INSERT INTO employees(tenant_id,id,lifecycle_status)
VALUES ('{tenant}','{actor}','ACTIVE'), ('{tenant}','{target}','ACTIVE')
ON CONFLICT(tenant_id,id) DO NOTHING;
INSERT INTO applications(tenant_id,id,lifecycle_status)
VALUES ('{tenant}','{application}','ACTIVE') ON CONFLICT(tenant_id,id) DO NOTHING;
INSERT INTO application_roles(tenant_id,application_id,role_key)
VALUES ('{tenant}','{application}','READER') ON CONFLICT DO NOTHING;
INSERT INTO employee_identities(tenant_id,employee_id,issuer,subject)
VALUES ('{tenant}','{actor}','{ISSUER}','{subject}') ON CONFLICT DO NOTHING;
INSERT INTO employee_permissions(tenant_id,employee_id,permission_key)
VALUES ('{tenant}','{actor}','ACCESS_REQUEST_CREATE') ON CONFLICT DO NOTHING;
INSERT INTO tenants(id,name)
VALUES ('{foreign_tenant}','iam-lab-foreign-{foreign_tenant}') ON CONFLICT(id) DO NOTHING;
INSERT INTO employees(tenant_id,id,lifecycle_status)
VALUES ('{foreign_tenant}','{foreign_employee}','ACTIVE') ON CONFLICT DO NOTHING;
COMMIT;
"""
    psql(sql)
    # Verify the actual mapping and grant; never assume ON CONFLICT implies the right state.
    verify = f"""SELECT count(*) FROM employees e JOIN employee_identities i
 ON e.tenant_id=i.tenant_id AND e.id=i.employee_id
 WHERE e.tenant_id='{tenant}' AND e.id='{actor}' AND e.lifecycle_status='ACTIVE'
 AND i.issuer='{ISSUER}' AND i.subject='{subject}';
"""
    rows = psql(verify).splitlines()
    if len(rows) < 1 or rows[0].strip() != "1":
        raise RuntimeError("Refusing success: active employee/issuer/subject mapping did not match")
    verify_permission = f"""SELECT count(*) FROM employee_permissions
WHERE tenant_id='{tenant}' AND employee_id='{actor}' AND permission_key='ACCESS_REQUEST_CREATE';
"""
    grants = psql(verify_permission).splitlines()
    expected = "0" if args.revoke else "1"
    if len(grants) < 1 or grants[0].strip() != expected:
        raise RuntimeError("Permission readback mismatch; no acceptance evidence")
    print("PASS: active Keycloak issuer+subject mapped to synthetic employee")
    print("PASS: ACCESS_REQUEST_CREATE grant " + ("revoked" if args.revoke else "present"))
    if not args.revoke:
        print("LAB_ACTOR_EMPLOYEE_ID=" + str(actor))
        print("LAB_TARGET_EMPLOYEE_ID=" + str(target))
        print("LAB_APPLICATION_ID=" + str(application))
        print("LAB_FOREIGN_EMPLOYEE_ID=" + str(foreign_employee))
    print("No production tenants, users or application permissions touched.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError, KeyError, ValueError) as exc:
        print("FAIL:", str(exc).splitlines()[0], file=sys.stderr)
        sys.exit(1)
