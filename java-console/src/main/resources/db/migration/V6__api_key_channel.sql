-- V6: API Key 认证 + 渠道管理

-- API Key 表
CREATE TABLE api_key (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    key_name VARCHAR(100) NOT NULL,
    api_key VARCHAR(128) UNIQUE NOT NULL,
    agent_id BIGINT REFERENCES agent_definition(id),
    user_id BIGINT REFERENCES sys_user(id),
    rate_limit INT DEFAULT 0,
    is_active BOOLEAN DEFAULT true,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    expires_at TIMESTAMP
);

-- 预设一个开发用 API Key
INSERT INTO api_key (key_name, api_key, agent_id, user_id, rate_limit) VALUES
('Dev Key', 'ak-dev-0000', null, 1, 1000);
