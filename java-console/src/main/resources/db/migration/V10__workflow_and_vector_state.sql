ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS index_version INT NOT NULL DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_embedding
    ON knowledge_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS tenant_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS current_step INT NOT NULL DEFAULT 0;
ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS input JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);
ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();
CREATE UNIQUE INDEX IF NOT EXISTS uq_workspace_execution_idempotency
    ON workspace_execution(tenant_id, resource_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_workspace_execution_queue
    ON workspace_execution(status, updated_at) WHERE status IN ('queued', 'running', 'retrying');

CREATE TABLE workflow_execution_event (
    id BIGSERIAL PRIMARY KEY,
    execution_id BIGINT NOT NULL REFERENCES workspace_execution(id) ON DELETE CASCADE,
    node_id VARCHAR(100),
    event_type VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_workflow_event_execution ON workflow_execution_event(execution_id, id);
