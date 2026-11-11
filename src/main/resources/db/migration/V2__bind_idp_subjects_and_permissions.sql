-- Explicit local authorization binding, additive to V1. Never modify an applied migration.
CREATE TABLE employee_identities (
    tenant_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    PRIMARY KEY (tenant_id, issuer, subject),
    UNIQUE (tenant_id, employee_id, issuer),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id)
);

CREATE TABLE employee_permissions (
    tenant_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    permission_key VARCHAR(64) NOT NULL
        CHECK (permission_key IN ('ACCESS_REQUEST_CREATE')),
    PRIMARY KEY (tenant_id, employee_id, permission_key),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id)
);
