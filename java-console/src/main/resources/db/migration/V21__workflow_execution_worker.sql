ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS worker_id VARCHAR(100);
ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMP;
ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS max_attempts INT NOT NULL DEFAULT 3;
ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE workspace_execution ADD COLUMN IF NOT EXISTS last_error VARCHAR(1000);

DROP INDEX IF EXISTS idx_workspace_execution_queue;
CREATE INDEX idx_workspace_execution_queue
    ON workspace_execution(status, next_attempt_at, started_at)
    WHERE status IN ('queued', 'running', 'retrying');
