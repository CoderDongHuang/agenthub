-- Tenant-scoped indexes for the management console's hottest list and summary queries.
CREATE INDEX IF NOT EXISTS idx_workspace_execution_tenant_resource_started
    ON workspace_execution(tenant_id, resource_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_tenant_created
    ON audit_log(tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_tenant_type_created
    ON audit_log(tenant_id, event_type, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_token_usage_tenant_created
    ON token_usage(tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_token_usage_tenant_agent_created
    ON token_usage(tenant_id, agent_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_approval_tenant_status_created
    ON approval_request(tenant_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tool_tenant_created
    ON tool_definition(tenant_id, created_at DESC);
