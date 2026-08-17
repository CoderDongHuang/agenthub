-- V15: enterprise identity, data governance, policy, approval operations and recovery

CREATE TABLE identity_provider (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    provider_type VARCHAR(20) NOT NULL CHECK (provider_type IN ('oidc', 'saml')),
    name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    validation_status VARCHAR(20) NOT NULL DEFAULT 'unverified',
    last_validated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE scim_token (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    token_prefix VARCHAR(16) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMP,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE access_policy (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    effect VARCHAR(10) NOT NULL CHECK (effect IN ('allow', 'deny')),
    priority INT NOT NULL DEFAULT 100,
    resource_type VARCHAR(60) NOT NULL,
    action_pattern VARCHAR(120) NOT NULL DEFAULT '*',
    conditions JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE tenant_key_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    version INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'retired')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    retired_at TIMESTAMP,
    UNIQUE (tenant_id, version)
);
CREATE UNIQUE INDEX uq_tenant_active_key ON tenant_key_version(tenant_id) WHERE status = 'active';

CREATE TABLE secret_record (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    secret_key VARCHAR(160) NOT NULL,
    ciphertext TEXT NOT NULL,
    nonce VARCHAR(32) NOT NULL,
    key_version INT NOT NULL,
    description TEXT,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, secret_key)
);

CREATE TABLE data_retention_policy (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    data_type VARCHAR(60) NOT NULL,
    retention_days INT NOT NULL CHECK (retention_days BETWEEN 1 AND 36500),
    action VARCHAR(20) NOT NULL DEFAULT 'delete' CHECK (action IN ('delete', 'anonymize')),
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at TIMESTAMP,
    last_affected_rows BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, data_type)
);

CREATE TABLE governance_job (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    job_type VARCHAR(30) NOT NULL CHECK (job_type IN ('export', 'backup', 'restore_drill', 'migration')),
    status VARCHAR(20) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    result JSONB NOT NULL DEFAULT '{}'::jsonb,
    checksum VARCHAR(64),
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE TABLE deletion_certificate (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    subject_type VARCHAR(60) NOT NULL,
    subject_ref VARCHAR(160) NOT NULL,
    rows_affected BIGINT NOT NULL DEFAULT 0,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    evidence_hash VARCHAR(64) NOT NULL,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE approval_policy (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('auto_approve', 'single', 'dual', 'reject')),
    conditions JSONB NOT NULL DEFAULT '{}'::jsonb,
    sla_minutes INT NOT NULL DEFAULT 60 CHECK (sla_minutes BETWEEN 1 AND 43200),
    escalation_role VARCHAR(60),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE on_call_schedule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    primary_user_id BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
    backup_user_id BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
    timezone VARCHAR(60) NOT NULL DEFAULT 'Asia/Shanghai',
    active_from TIME NOT NULL DEFAULT '00:00',
    active_to TIME NOT NULL DEFAULT '23:59',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS policy_id BIGINT REFERENCES approval_policy(id) ON DELETE SET NULL;
ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS risk_score INT NOT NULL DEFAULT 0;
ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS amount DECIMAL(18,2);
ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS data_classification VARCHAR(30);
ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS caller_type VARCHAR(30);
ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS due_at TIMESTAMP;
ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS assigned_to BIGINT REFERENCES sys_user(id) ON DELETE SET NULL;
ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS escalated_at TIMESTAMP;
ALTER TABLE approval_request ADD COLUMN IF NOT EXISTS sla_status VARCHAR(20) NOT NULL DEFAULT 'within_sla';

CREATE INDEX idx_identity_provider_tenant ON identity_provider(tenant_id, updated_at DESC);
CREATE INDEX idx_scim_token_tenant ON scim_token(tenant_id, created_at DESC);
CREATE INDEX idx_access_policy_decision ON access_policy(tenant_id, resource_type, enabled, priority);
CREATE INDEX idx_secret_record_tenant ON secret_record(tenant_id, updated_at DESC);
CREATE INDEX idx_retention_policy_tenant ON data_retention_policy(tenant_id, enabled);
CREATE INDEX idx_governance_job_tenant ON governance_job(tenant_id, created_at DESC);
CREATE INDEX idx_deletion_certificate_tenant ON deletion_certificate(tenant_id, created_at DESC);
CREATE INDEX idx_approval_policy_tenant ON approval_policy(tenant_id, enabled, priority);
CREATE INDEX idx_approval_sla ON approval_request(tenant_id, status, due_at) WHERE status = 'pending';

INSERT INTO sys_permission (perm_name, perm_code, description)
VALUES ('管理企业治理', 'governance:manage', '管理身份、密钥、合规、护栏、审批和恢复能力')
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id FROM sys_role role, sys_permission permission
WHERE role.role_code = 'admin' AND permission.perm_code = 'governance:manage'
ON CONFLICT (role_id, permission_id) DO NOTHING;
