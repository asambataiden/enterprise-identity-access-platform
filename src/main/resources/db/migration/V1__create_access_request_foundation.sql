-- Milestone 01: internal access-request persistence only.
-- No public IAM API, authentication, approval or provisioning is implemented.
CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE
);

CREATE TABLE employees (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    id UUID NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL CHECK (lifecycle_status IN ('ACTIVE', 'DISABLED')),
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE applications (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    id UUID NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL CHECK (lifecycle_status IN ('ACTIVE', 'DISABLED')),
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE application_roles (
    tenant_id UUID NOT NULL,
    application_id UUID NOT NULL,
    role_key VARCHAR(64) NOT NULL CHECK (length(trim(role_key)) > 0),
    PRIMARY KEY (tenant_id, application_id, role_key),
    FOREIGN KEY (tenant_id, application_id) REFERENCES applications(tenant_id, id)
);

CREATE TABLE access_requests (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    requester_id UUID NOT NULL,
    employee_id UUID NOT NULL,
    application_id UUID NOT NULL,
    role_key VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (length(trim(idempotency_key)) > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status = 'PENDING'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_access_requests_tenant_key UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_access_request_requester FOREIGN KEY (tenant_id, requester_id)
        REFERENCES employees (tenant_id, id),
    CONSTRAINT fk_access_request_employee FOREIGN KEY (tenant_id, employee_id)
        REFERENCES employees (tenant_id, id),
    CONSTRAINT fk_access_request_role FOREIGN KEY (tenant_id, application_id, role_key)
        REFERENCES application_roles (tenant_id, application_id, role_key)
);

CREATE INDEX idx_access_requests_tenant_created
    ON access_requests(tenant_id, created_at DESC, id DESC);
