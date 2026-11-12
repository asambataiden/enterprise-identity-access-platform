#!/usr/bin/env python3
"""Guarded, opt-in IAM lab self-approval test grant; NEVER a production operator tool.

Temporarily grants only ACCESS_REQUEST_DECIDE to the synthetic *requester*
(identity iam-lab-test-admin) for a live self-approval negative test.
--revoke removes only this grant, regardless of the request's current state.
No user, password, Keycloak scope, or other grant is modified.
"""
import argparse
import importlib.util
import os
from pathlib import Path
import sys
import uuid

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "iam_sod_db_guard", HERE / "seed-authorized-iam-fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

ISSUER = "http://127.0.0.1:8281/realms/iam-lab"
SELF_KEY = "iam-lab-03d-self"


def identity(tenant):
    requester = uuid.uuid5(tenant, "iam-lab-test-admin:employee")
    separate_approver = uuid.uuid5(tenant, "iam-lab-test-approver:employee")
    request_id = uuid.uuid5(tenant, "iam-lab-03d-self-approval-request")
    return requester, separate_approver, request_id


def count(sql):
    value = fixture.psql(sql)
    if value not in ("0", "1"):
        raise RuntimeError("Unexpected synthetic fixture count; refusing change")
    return value == "1"


def check_preconditions(tenant, requester, approver, request_id):
    if not count(f"""SELECT count(*) FROM employees e
JOIN employee_identities i ON i.tenant_id=e.tenant_id AND i.employee_id=e.id
WHERE e.tenant_id='{tenant}' AND e.id='{requester}'
  AND e.lifecycle_status='ACTIVE' AND i.issuer='{ISSUER}';"""):
        raise RuntimeError("Synthetic requester must be an active, Keycloak-bound employee")
    if not count(f"""SELECT count(*) FROM access_requests
WHERE tenant_id='{tenant}' AND id='{request_id}'
  AND requester_id='{requester}' AND idempotency_key='{SELF_KEY}'
  AND status='PENDING';"""):
        raise RuntimeError("Self-approval fixture must exist and remain PENDING")
    if count(f"""SELECT count(*) FROM employee_permissions WHERE tenant_id='{tenant}'
AND employee_id='{requester}' AND permission_key='ACCESS_REQUEST_CREATE';"""):
        raise RuntimeError("Requester CREATE permission must remain revoked")
    if count(f"""SELECT count(*) FROM employee_permissions WHERE tenant_id='{tenant}'
AND employee_id='{approver}' AND permission_key='ACCESS_REQUEST_DECIDE';"""):
        raise RuntimeError("Separate approver DECIDE grant must remain revoked")


def change(tenant, requester, revoke):
    if revoke:
        fixture.psql(f"""BEGIN;
DELETE FROM employee_permissions WHERE tenant_id='{tenant}'
 AND employee_id='{requester}' AND permission_key='ACCESS_REQUEST_DECIDE';
COMMIT;""")
    else:
        fixture.psql(f"""BEGIN;
INSERT INTO employee_permissions(tenant_id,employee_id,permission_key)
VALUES ('{tenant}','{requester}','ACCESS_REQUEST_DECIDE')
ON CONFLICT DO NOTHING;
COMMIT;""")
    enabled = count(f"""SELECT count(*) FROM employee_permissions WHERE tenant_id='{tenant}'
AND employee_id='{requester}' AND permission_key='ACCESS_REQUEST_DECIDE';""")
    if enabled == revoke:
        raise RuntimeError("Requester DECIDE grant readback mismatch")
    return enabled


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    flags = parser.add_mutually_exclusive_group(required=True)
    flags.add_argument("--grant", action="store_true", help="Explicit temporary local grant for live SoD test")
    flags.add_argument("--revoke", action="store_true", help="Remove the temporary requester grant")
    args = parser.parse_args()
    try:
        tenant = uuid.UUID(os.environ.get("IAM_LAB_TENANT_ID", ""))
    except ValueError:
        parser.error("Set IAM_LAB_TENANT_ID to the synthetic fixture UUID")
    expected = uuid.UUID("8230545d-3560-41e6-af0b-b1ef50778606")
    if tenant != expected:
        raise RuntimeError("Refusing non-canonical local lab tenant")
    requester, approver, request_id = identity(tenant)
    if args.grant:
        check_preconditions(tenant, requester, approver, request_id)
    # On revoke, intentionally do not require a pending request or live account.
    # The shared fixture.psql still enforces the exact container and loopback port.
    enabled = change(tenant, requester, args.revoke)
    print("PASS: synthetic requester DECIDE grant " + ("present" if enabled else "revoked"))
    if enabled:
        print("LAB_03D_SELF_REQUEST_ID=" + str(request_id))
        print("WARNING: temporary lab grant active. Always run --revoke after the test.")
    else:
        print("PASS: cleanup complete; no Keycloak users or unrelated grants changed")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        print("FAIL:", str(error).splitlines()[0], file=sys.stderr)
        sys.exit(1)
