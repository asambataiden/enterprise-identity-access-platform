#!/usr/bin/env python3
"""Seed TWO deterministic PENDING requests only in the guarded localhost IAM DB.

This is a synthetic persistence fixture, NOT evidence of authenticated request
creation. Does not grant permissions or reset any existing approval.
"""
import importlib.util
import os
from pathlib import Path
import sys
import uuid

path = Path(__file__).with_name("seed-authorized-iam-fixture.py")
spec = importlib.util.spec_from_file_location("iam_approval_fixture",path)
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

def main():
    try:
        tenant=uuid.UUID(os.environ.get("IAM_LAB_TENANT_ID",""))
    except ValueError:
        raise RuntimeError("Set IAM_LAB_TENANT_ID to the synthetic tenant UUID") from None
    requester=uuid.uuid5(tenant,"iam-lab-test-admin:employee")
    target=uuid.uuid5(tenant,"iam-lab-test-employee:target")
    app=uuid.uuid5(tenant,"iam-lab-test-app:application")
    approver=uuid.uuid5(tenant,"iam-lab-test-approver:employee")
    if fixture.psql(f"""SELECT count(*) FROM employees WHERE tenant_id='{tenant}' AND id='{approver}' AND lifecycle_status='ACTIVE';""") != "1":
        raise RuntimeError("Provision and verify separate synthetic approver first")
    if fixture.psql(f"""SELECT count(*) FROM employee_permissions WHERE tenant_id='{tenant}' AND employee_id='{requester}' AND permission_key='ACCESS_REQUEST_CREATE';""") != "0":
        raise RuntimeError("Requester CREATE grant must remain revoked for this test")
    if fixture.psql(f"""SELECT count(*) FROM employees WHERE tenant_id='{tenant}' AND id IN ('{requester}','{target}') AND lifecycle_status='ACTIVE';""") != "2":
        raise RuntimeError("Expected requester/target test employees not present")
    if fixture.psql(f"""SELECT count(*) FROM application_roles WHERE tenant_id='{tenant}' AND application_id='{app}' AND role_key='READER';""") != "1":
        raise RuntimeError("Expected local test application role not present")
    scenarios={"approve":uuid.uuid5(tenant,"iam-lab-03d-approval-request"),
               "reject":uuid.uuid5(tenant,"iam-lab-03d-rejection-request"),
               "self":uuid.uuid5(tenant,"iam-lab-03d-self-approval-request")}
    values=",".join(f"('{id}','{tenant}','{requester}','{target}','{app}','READER','iam-lab-03d-{label}')" for label,id in scenarios.items())
    fixture.psql(f"""BEGIN;
INSERT INTO access_requests(id,tenant_id,requester_id,employee_id,application_id,role_key,idempotency_key)
VALUES {values} ON CONFLICT (tenant_id,idempotency_key) DO NOTHING;
COMMIT;""")
    for label,request_id in scenarios.items():
        check=fixture.psql(f"""SELECT count(*) FROM access_requests
WHERE id='{request_id}' AND tenant_id='{tenant}' AND requester_id='{requester}'
AND employee_id='{target}' AND application_id='{app}' AND role_key='READER'
AND idempotency_key='iam-lab-03d-{label}' AND status IN ('PENDING','APPROVED','REJECTED');""")
        if check!="1":
            raise RuntimeError("Existing synthetic request differs from expected fixture; no acceptance evidence")
    print("PASS: synthetic decision request IDs verified; no access permission granted")
    for label,id in scenarios.items():
        print("LAB_03D_"+label.upper()+"_REQUEST_ID="+str(id))
    print("Existing decisions are not overwritten; check status before repeating a successful approval")

if __name__=="__main__":
    try: main()
    except (RuntimeError, OSError, ValueError) as error:
        print("FAIL:",str(error).splitlines()[0],file=sys.stderr)
        sys.exit(1)
