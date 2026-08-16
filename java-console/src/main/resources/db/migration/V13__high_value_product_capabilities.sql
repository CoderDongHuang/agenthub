-- V13: release management, evaluations, traces, resilient routing, knowledge sync and workflow triggers

CREATE TABLE agent_version (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    agent_id BIGINT NOT NULL REFERENCES agent_definition(id) ON DELETE CASCADE,
    version_no INT NOT NULL,
    config JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    rollout_percent INT NOT NULL DEFAULT 0 CHECK (rollout_percent BETWEEN 0 AND 100),
    evaluation_status VARCHAR(20) NOT NULL DEFAULT 'not_run',
    change_note TEXT,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP,
    UNIQUE (agent_id, version_no)
);
CREATE INDEX idx_agent_version_tenant_agent ON agent_version(tenant_id, agent_id, version_no DESC);
CREATE INDEX idx_agent_version_release ON agent_version(tenant_id, status, published_at DESC);

ALTER TABLE agent_definition ADD COLUMN IF NOT EXISTS current_version_id BIGINT REFERENCES agent_version(id);

INSERT INTO agent_version (tenant_id, agent_id, version_no, config, status, rollout_percent,
                           evaluation_status, change_note, created_by, created_at, published_at)
SELECT tenant_id, id, 1,
       jsonb_build_object(
           'name', name, 'description', COALESCE(description, ''), 'systemPrompt', system_prompt,
           'model', model, 'temperature', temperature, 'maxTokens', max_tokens, 'icon', COALESCE(icon, '')
       ),
       CASE WHEN status = 'published' THEN 'published' ELSE 'draft' END,
       CASE WHEN status = 'published' THEN 100 ELSE 0 END,
       'not_run', 'Imported from the existing Agent definition', created_by, created_at, published_at
FROM agent_definition
ON CONFLICT (agent_id, version_no) DO NOTHING;

UPDATE agent_definition definition
SET current_version_id = version.id
FROM agent_version version
WHERE version.agent_id = definition.id AND version.version_no = 1 AND definition.current_version_id IS NULL;

CREATE TABLE evaluation_dataset (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('prompt', 'tool', 'rag')),
    pass_threshold DECIMAL(5,2) NOT NULL DEFAULT 80,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_evaluation_dataset_tenant ON evaluation_dataset(tenant_id, updated_at DESC);

CREATE TABLE evaluation_case (
    id BIGSERIAL PRIMARY KEY,
    dataset_id BIGINT NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    expected JSONB NOT NULL DEFAULT '{}'::jsonb,
    assertion_type VARCHAR(30) NOT NULL DEFAULT 'contains',
    weight DECIMAL(6,2) NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_evaluation_case_dataset ON evaluation_case(dataset_id, id);

CREATE TABLE evaluation_run (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    dataset_id BIGINT NOT NULL REFERENCES evaluation_dataset(id) ON DELETE CASCADE,
    agent_id BIGINT REFERENCES agent_definition(id) ON DELETE SET NULL,
    agent_version_id BIGINT REFERENCES agent_version(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL,
    score DECIMAL(6,2) NOT NULL DEFAULT 0,
    passed_cases INT NOT NULL DEFAULT 0,
    total_cases INT NOT NULL DEFAULT 0,
    details JSONB NOT NULL DEFAULT '[]'::jsonb,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);
CREATE INDEX idx_evaluation_run_tenant ON evaluation_run(tenant_id, started_at DESC);

CREATE TABLE execution_trace (
    trace_id UUID PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    agent_id BIGINT NOT NULL,
    agent_version_id BIGINT REFERENCES agent_version(id) ON DELETE SET NULL,
    model VARCHAR(100),
    route_reason VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'running',
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    total_cost DECIMAL(14,8) NOT NULL DEFAULT 0,
    latency_ms BIGINT,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);
CREATE INDEX idx_execution_trace_tenant ON execution_trace(tenant_id, started_at DESC);
CREATE INDEX idx_execution_trace_session ON execution_trace(tenant_id, session_id);

CREATE TABLE execution_span (
    id BIGSERIAL PRIMARY KEY,
    trace_id UUID NOT NULL REFERENCES execution_trace(trace_id) ON DELETE CASCADE,
    span_type VARCHAR(30) NOT NULL,
    name VARCHAR(150) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'running',
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    output JSONB NOT NULL DEFAULT '{}'::jsonb,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    duration_ms BIGINT
);
CREATE INDEX idx_execution_span_trace ON execution_span(trace_id, id);

CREATE TABLE model_endpoint (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    model VARCHAR(100) NOT NULL,
    provider VARCHAR(60) NOT NULL,
    region VARCHAR(40) NOT NULL DEFAULT 'global',
    base_url VARCHAR(500),
    cost_per_1k DECIMAL(12,6) NOT NULL DEFAULT 0,
    quality_score DECIMAL(5,2) NOT NULL DEFAULT 80,
    latency_slo_ms INT NOT NULL DEFAULT 5000,
    status VARCHAR(20) NOT NULL DEFAULT 'unknown',
    circuit_state VARCHAR(20) NOT NULL DEFAULT 'closed',
    consecutive_failures INT NOT NULL DEFAULT 0,
    last_latency_ms BIGINT,
    last_error VARCHAR(500),
    last_checked_at TIMESTAMP,
    recover_after TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, model, region)
);
CREATE INDEX idx_model_endpoint_route ON model_endpoint(tenant_id, circuit_state, status, quality_score DESC);

INSERT INTO model_endpoint (tenant_id, model, provider, region, quality_score, status)
SELECT DISTINCT tenant_id, model,
       CASE
           WHEN model LIKE 'gpt-%' THEN 'openai'
           WHEN model LIKE 'claude-%' THEN 'anthropic'
           WHEN model LIKE 'gemini-%' THEN 'google'
           WHEN model LIKE 'qwen-%' THEN 'alibaba'
           WHEN model LIKE 'deepseek-%' THEN 'deepseek'
           ELSE 'custom'
       END,
       'global', 85, 'unknown'
FROM agent_definition
ON CONFLICT (tenant_id, model, region) DO NOTHING;

CREATE TABLE knowledge_source (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    source_key VARCHAR(150) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    inherited_acl JSONB NOT NULL DEFAULT '{}'::jsonb,
    sync_cursor VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    last_sync_at TIMESTAMP,
    last_error VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, kb_id, source_key)
);
CREATE INDEX idx_knowledge_source_tenant ON knowledge_source(tenant_id, kb_id, updated_at DESC);

ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS source_id BIGINT REFERENCES knowledge_source(id) ON DELETE SET NULL;
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS external_id VARCHAR(255);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS source_version INT NOT NULL DEFAULT 1;
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS inherited_acl JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS citation JSONB NOT NULL DEFAULT '{}'::jsonb;
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_document_source_external
    ON knowledge_document(source_id, external_id) WHERE source_id IS NOT NULL AND external_id IS NOT NULL;

CREATE TABLE knowledge_evaluation_run (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    query TEXT NOT NULL,
    expected_document_id BIGINT REFERENCES knowledge_document(id) ON DELETE SET NULL,
    matched_document_id BIGINT REFERENCES knowledge_document(id) ON DELETE SET NULL,
    score DECIMAL(6,4) NOT NULL DEFAULT 0,
    passed BOOLEAN NOT NULL DEFAULT FALSE,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE workflow_template (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    template_key VARCHAR(120) NOT NULL,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    config JSONB NOT NULL,
    version INT NOT NULL DEFAULT 1,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, template_key, version)
);

INSERT INTO workflow_template (tenant_id, template_key, name, description, config, created_by)
SELECT tenant_id, resource_key, name, description, config, created_by
FROM workspace_resource WHERE resource_type = 'workflow'
ON CONFLICT (tenant_id, template_key, version) DO NOTHING;

CREATE TABLE workflow_trigger (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    workflow_id BIGINT NOT NULL REFERENCES workspace_resource(id) ON DELETE CASCADE,
    trigger_type VARCHAR(20) NOT NULL CHECK (trigger_type IN ('schedule', 'webhook')),
    trigger_key VARCHAR(120) NOT NULL,
    secret_hash VARCHAR(64),
    cron_expression VARCHAR(100),
    interval_seconds INT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at TIMESTAMP,
    last_run_at TIMESTAMP,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, trigger_key)
);
CREATE INDEX idx_workflow_trigger_due ON workflow_trigger(enabled, next_run_at)
    WHERE enabled = TRUE AND trigger_type = 'schedule';
