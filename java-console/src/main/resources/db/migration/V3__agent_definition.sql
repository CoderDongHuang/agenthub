-- V3: Agent 定义表 + Agent-工具关联表

-- Agent 定义表
CREATE TABLE agent_definition (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    system_prompt TEXT NOT NULL,
    model VARCHAR(50) NOT NULL DEFAULT 'gpt-4o',
    temperature DECIMAL(3,2) DEFAULT 0.7,
    max_tokens INT DEFAULT 4096,
    status VARCHAR(20) DEFAULT 'draft',
    icon VARCHAR(50),
    created_by BIGINT REFERENCES sys_user(id),
    published_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Agent 会话表
CREATE TABLE agent_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) UNIQUE NOT NULL,
    agent_id BIGINT REFERENCES agent_definition(id),
    user_id BIGINT REFERENCES sys_user(id),
    channel VARCHAR(20) DEFAULT 'web',
    status VARCHAR(20) DEFAULT 'active',
    message_count INT DEFAULT 0,
    total_tokens DECIMAL(10,2) DEFAULT 0,
    started_at TIMESTAMP DEFAULT NOW(),
    ended_at TIMESTAMP
);

-- 会话消息表
CREATE TABLE agent_message (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) REFERENCES agent_session(session_id),
    role VARCHAR(20) NOT NULL,
    content TEXT,
    tool_calls JSONB,
    token_count DECIMAL(10,2) DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 工具定义表（Phase 2-3 使用，Phase 1 预留）
CREATE TABLE tool_definition (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    tool_name VARCHAR(100) NOT NULL,
    tool_code VARCHAR(100) UNIQUE NOT NULL,
    description TEXT NOT NULL,
    category VARCHAR(50) NOT NULL DEFAULT '自定义',
    risk_level VARCHAR(20) NOT NULL DEFAULT 'low',
    json_schema JSONB NOT NULL DEFAULT '{}',
    python_module VARCHAR(255),
    rate_limit INT DEFAULT 0,
    timeout_seconds INT DEFAULT 30,
    status VARCHAR(20) DEFAULT 'active',
    registered_by BIGINT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Agent-工具关联表
CREATE TABLE agent_tool_binding (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT REFERENCES agent_definition(id) ON DELETE CASCADE,
    tool_id BIGINT REFERENCES tool_definition(id),
    is_enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 索引
CREATE INDEX idx_agent_tenant_status ON agent_definition(tenant_id, status);
CREATE INDEX idx_agent_session_agent ON agent_session(agent_id);
CREATE INDEX idx_agent_session_user ON agent_session(user_id);
CREATE INDEX idx_agent_message_session ON agent_message(session_id);
