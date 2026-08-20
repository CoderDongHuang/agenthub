-- V18: channel delivery operations, knowledge index operations, and multimodal review queues

CREATE TABLE channel_delivery (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    channel VARCHAR(30) NOT NULL,
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('inbound', 'outbound')),
    external_message_id VARCHAR(255) NOT NULL,
    conversation_key VARCHAR(255) NOT NULL,
    agent_id BIGINT,
    recipient_id VARCHAR(255),
    status VARCHAR(20) NOT NULL CHECK (status IN ('accepted', 'processing', 'delivered', 'retrying', 'dead_letter')),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INT NOT NULL DEFAULT 4 CHECK (max_attempts BETWEEN 1 AND 12),
    next_attempt_at TIMESTAMP,
    last_error VARCHAR(1000),
    receipt_at TIMESTAMP,
    replayed_from UUID REFERENCES channel_delivery(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (channel, external_message_id, direction)
);

CREATE TABLE channel_conversation (
    id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    channel VARCHAR(30) NOT NULL,
    conversation_key VARCHAR(255) NOT NULL,
    session_id VARCHAR(100) NOT NULL,
    agent_id BIGINT,
    context JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_message_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, channel, conversation_key)
);

CREATE TABLE channel_route_rule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    channel VARCHAR(30) NOT NULL DEFAULT '*',
    chat_type VARCHAR(20) NOT NULL DEFAULT '*',
    match_type VARCHAR(20) NOT NULL DEFAULT 'default' CHECK (match_type IN ('default', 'mention', 'keyword')),
    match_value VARCHAR(160) NOT NULL DEFAULT '',
    agent_id BIGINT NOT NULL,
    priority INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, name)
);

CREATE INDEX idx_channel_delivery_queue ON channel_delivery(status, next_attempt_at)
    WHERE status = 'retrying';
CREATE INDEX idx_channel_delivery_tenant ON channel_delivery(tenant_id, created_at DESC);
CREATE INDEX idx_channel_conversation_tenant ON channel_conversation(tenant_id, last_message_at DESC);
CREATE INDEX idx_channel_route_tenant ON channel_route_rule(tenant_id, enabled, priority);

CREATE TABLE knowledge_index_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    version_no INT NOT NULL,
    trigger_type VARCHAR(20) NOT NULL CHECK (trigger_type IN ('sync', 'manual')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('building', 'completed', 'partial', 'failed')),
    document_count INT NOT NULL DEFAULT 0,
    chunk_count INT NOT NULL DEFAULT 0,
    change_summary JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    UNIQUE (kb_id, version_no)
);

CREATE INDEX idx_knowledge_index_version ON knowledge_index_version(tenant_id, kb_id, version_no DESC);

ALTER TABLE multimodal_job
    ADD COLUMN IF NOT EXISTS review_status VARCHAR(20) NOT NULL DEFAULT 'not_required',
    ADD COLUMN IF NOT EXISTS reviewer_id BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS review_notes TEXT,
    ADD COLUMN IF NOT EXISTS corrected_extraction JSONB,
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP,
    ADD CONSTRAINT chk_multimodal_review_status
        CHECK (review_status IN ('not_required', 'pending', 'in_review', 'approved', 'rejected'));

UPDATE multimodal_job SET review_status = 'pending' WHERE review_required = TRUE AND review_status = 'not_required';
CREATE INDEX idx_multimodal_review_queue ON multimodal_job(tenant_id, review_status, created_at DESC);

INSERT INTO sys_permission (perm_name, perm_code, description)
VALUES ('管理渠道与知识运营', 'operations:manage', '管理渠道投递、知识索引和多模态复核')
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT role.id, permission.id FROM sys_role role, sys_permission permission
WHERE role.role_code = 'admin' AND permission.perm_code = 'operations:manage'
ON CONFLICT (role_id, permission_id) DO NOTHING;
