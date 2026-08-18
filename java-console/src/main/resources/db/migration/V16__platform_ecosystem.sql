-- V16: platform ecosystem capabilities

CREATE TABLE ecosystem_package (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    package_type VARCHAR(20) NOT NULL CHECK (package_type IN ('tool', 'plugin', 'mcp')),
    package_name VARCHAR(160) NOT NULL,
    version VARCHAR(40) NOT NULL,
    visibility VARCHAR(20) NOT NULL DEFAULT 'private' CHECK (visibility IN ('private', 'tenant', 'public')),
    source_uri TEXT NOT NULL,
    source_digest VARCHAR(64) NOT NULL,
    artifact BYTEA NOT NULL,
    manifest JSONB NOT NULL DEFAULT '{}'::jsonb,
    signature VARCHAR(128) NOT NULL,
    signature_algorithm VARCHAR(30) NOT NULL DEFAULT 'HMAC-SHA256',
    signer VARCHAR(120) NOT NULL,
    compatibility JSONB NOT NULL DEFAULT '{}'::jsonb,
    scan_status VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (scan_status IN ('pending', 'passed', 'warning', 'blocked')),
    risk_score INT NOT NULL DEFAULT 0 CHECK (risk_score BETWEEN 0 AND 100),
    scan_findings JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_by BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, package_name, version)
);

CREATE TABLE mcp_connection (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    direction VARCHAR(20) NOT NULL CHECK (direction IN ('client', 'server')),
    transport VARCHAR(20) NOT NULL CHECK (transport IN ('http', 'sse', 'stdio')),
    endpoint TEXT NOT NULL,
    protocol_version VARCHAR(30) NOT NULL DEFAULT '2025-03-26',
    auth_secret_ref VARCHAR(180),
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'unverified' CHECK (status IN ('unverified', 'healthy', 'degraded', 'offline')),
    last_probe JSONB,
    last_probed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

CREATE TABLE developer_app (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    app_name VARCHAR(120) NOT NULL,
    public_key VARCHAR(80) UNIQUE NOT NULL,
    secret_ciphertext TEXT NOT NULL,
    secret_nonce VARCHAR(64) NOT NULL,
    secret_key_version INT NOT NULL,
    api_version VARCHAR(20) NOT NULL DEFAULT 'v1',
    quota_per_minute INT NOT NULL DEFAULT 60 CHECK (quota_per_minute BETWEEN 1 AND 100000),
    allowed_operations JSONB NOT NULL DEFAULT '["platform.echo"]'::jsonb,
    tenant_route VARCHAR(120) NOT NULL DEFAULT 'primary',
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'revoked')),
    created_by BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, app_name)
);

CREATE TABLE gateway_nonce (
    app_id BIGINT NOT NULL REFERENCES developer_app(id) ON DELETE CASCADE,
    nonce VARCHAR(120) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY (app_id, nonce)
);

CREATE TABLE gateway_usage_window (
    app_id BIGINT NOT NULL REFERENCES developer_app(id) ON DELETE CASCADE,
    window_start TIMESTAMP NOT NULL,
    request_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (app_id, window_start)
);

CREATE TABLE multimodal_job (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    input_digest VARCHAR(64) NOT NULL,
    input_bytes BIGINT NOT NULL,
    pipeline VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('completed', 'needs_provider', 'failed')),
    extraction JSONB NOT NULL DEFAULT '{}'::jsonb,
    provider VARCHAR(80),
    review_required BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE worker_pool (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    pool_name VARCHAR(120) NOT NULL,
    region VARCHAR(80) NOT NULL,
    min_replicas INT NOT NULL DEFAULT 1 CHECK (min_replicas >= 0),
    max_replicas INT NOT NULL DEFAULT 10 CHECK (max_replicas >= min_replicas),
    target_queue_depth INT NOT NULL DEFAULT 10 CHECK (target_queue_depth > 0),
    current_replicas INT NOT NULL DEFAULT 1 CHECK (current_replicas >= 0),
    desired_replicas INT NOT NULL DEFAULT 1 CHECK (desired_replicas >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'ready',
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, pool_name, region)
);

CREATE TABLE resilience_drill (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    drill_type VARCHAR(30) NOT NULL CHECK (drill_type IN ('regional_failover', 'worker_recovery', 'dependency_probe')),
    source_region VARCHAR(80),
    target_region VARCHAR(80),
    status VARCHAR(20) NOT NULL CHECK (status IN ('passed', 'warning', 'failed')),
    rto_seconds INT,
    rpo_seconds INT,
    evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
    executed_by BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ecosystem_package_tenant ON ecosystem_package(tenant_id, package_type, updated_at DESC);
CREATE INDEX idx_mcp_connection_tenant ON mcp_connection(tenant_id, updated_at DESC);
CREATE INDEX idx_developer_app_tenant ON developer_app(tenant_id, created_at DESC);
CREATE INDEX idx_gateway_nonce_expiry ON gateway_nonce(expires_at);
CREATE INDEX idx_gateway_usage_window ON gateway_usage_window(window_start);
CREATE INDEX idx_multimodal_job_tenant ON multimodal_job(tenant_id, created_at DESC);
CREATE INDEX idx_worker_pool_tenant ON worker_pool(tenant_id, region);
CREATE INDEX idx_resilience_drill_tenant ON resilience_drill(tenant_id, created_at DESC);

INSERT INTO sys_permission (perm_name, perm_code, description)
VALUES ('管理平台生态', 'ecosystem:manage', '管理 SDK、MCP、API 网关、多模态、部署和插件供应链')
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id FROM sys_role role, sys_permission permission
WHERE role.role_code = 'admin' AND permission.perm_code = 'ecosystem:manage'
ON CONFLICT (role_id, permission_id) DO NOTHING;
