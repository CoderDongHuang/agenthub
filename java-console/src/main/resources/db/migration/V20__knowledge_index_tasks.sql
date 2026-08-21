CREATE TABLE knowledge_index_task (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    kb_id BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    document_id BIGINT NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    version_id BIGINT NOT NULL REFERENCES knowledge_index_version(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','processing','retrying','completed','failed')),
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 4,
    worker_id VARCHAR(100),
    lease_expires_at TIMESTAMP,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (version_id, document_id)
);

CREATE INDEX idx_knowledge_index_task_queue ON knowledge_index_task(status, next_attempt_at)
    WHERE status IN ('queued','retrying');

