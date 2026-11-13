-- Milestone 04F-3A: privileged review must never reuse approval permission.
ALTER TABLE employee_permissions DROP CONSTRAINT chk_employee_permissions_key;
ALTER TABLE employee_permissions ADD CONSTRAINT chk_employee_permissions_key
  CHECK (permission_key IN (
    'ACCESS_REQUEST_CREATE', 'ACCESS_REQUEST_DECIDE',
    'PROVISIONING_QUARANTINE_REVIEW'
  ));
