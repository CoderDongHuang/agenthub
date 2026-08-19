-- V17: observability SLO fields, trace feedback and quality operations
ALTER TABLE execution_trace ADD COLUMN IF NOT EXISTS first_token_latency_ms BIGINT;
ALTER TABLE execution_trace ADD COLUMN IF NOT EXISTS retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE execution_trace ADD COLUMN IF NOT EXISTS cancellation_count INT NOT NULL DEFAULT 0;
ALTER TABLE execution_trace ADD COLUMN IF NOT EXISTS queue_depth INT NOT NULL DEFAULT 0;
ALTER TABLE execution_trace ADD COLUMN IF NOT EXISTS provider_error BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_execution_trace_observability
    ON execution_trace(tenant_id, started_at DESC, status, model);

CREATE TABLE IF NOT EXISTS trace_feedback (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    trace_id UUID NOT NULL REFERENCES execution_trace(trace_id) ON DELETE CASCADE,
    user_id BIGINT,
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment VARCHAR(2000),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, trace_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_trace_feedback_tenant ON trace_feedback(tenant_id, created_at DESC);
