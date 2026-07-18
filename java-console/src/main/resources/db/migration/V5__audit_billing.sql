-- V5: 审计日志 + Token 消耗 + 预算管控

-- 审计日志表
CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    event_type VARCHAR(50) NOT NULL,
    user_id BIGINT,
    username VARCHAR(50),
    agent_id BIGINT,
    agent_name VARCHAR(100),
    tool_name VARCHAR(100),
    session_id VARCHAR(64),
    action VARCHAR(200) NOT NULL,
    detail TEXT,
    result VARCHAR(20),
    ip_address VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW()
);

-- 索引
CREATE INDEX idx_audit_time ON audit_log(created_at DESC);
CREATE INDEX idx_audit_user ON audit_log(user_id, created_at DESC);
CREATE INDEX idx_audit_type ON audit_log(event_type, created_at DESC);

-- Token 消耗记录表
CREATE TABLE token_usage (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    agent_id BIGINT,
    session_id VARCHAR(64),
    user_id BIGINT,
    model VARCHAR(50) NOT NULL,
    input_tokens INT DEFAULT 0,
    output_tokens INT DEFAULT 0,
    cost DECIMAL(10,6) DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 月度预算表
CREATE TABLE budget (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    month VARCHAR(7) NOT NULL,
    total_budget DECIMAL(10,2),
    used_amount DECIMAL(10,2) DEFAULT 0,
    alert_threshold DECIMAL(5,2) DEFAULT 0.8,
    UNIQUE(tenant_id, month)
);

-- 默认预算（不限）
INSERT INTO budget (tenant_id, month, total_budget, used_amount) VALUES
(0, '2026-07', 1000.00, 0);
