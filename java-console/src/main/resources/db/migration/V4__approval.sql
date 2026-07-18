-- V4: 审批流 + 工具审批策略

-- 审批请求表
CREATE TABLE approval_request (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    session_id VARCHAR(64),
    agent_id BIGINT REFERENCES agent_definition(id),
    tool_id BIGINT REFERENCES tool_definition(id),
    tool_name VARCHAR(100),
    requester_id BIGINT REFERENCES sys_user(id),
    reason TEXT NOT NULL,
    context TEXT,
    status VARCHAR(20) DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT NOW(),
    resolved_at TIMESTAMP
);

-- 审批节点表
CREATE TABLE approval_node (
    id BIGSERIAL PRIMARY KEY,
    approval_request_id BIGINT REFERENCES approval_request(id) ON DELETE CASCADE,
    approver_id BIGINT REFERENCES sys_user(id),
    status VARCHAR(20) DEFAULT 'pending',
    comment TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    acted_at TIMESTAMP
);

-- 工具审批策略表
CREATE TABLE tool_approval_policy (
    id BIGSERIAL PRIMARY KEY,
    tool_id BIGINT REFERENCES tool_definition(id) ON DELETE CASCADE,
    risk_level VARCHAR(20) NOT NULL,
    approval_type VARCHAR(20) NOT NULL DEFAULT 'single',
    approver_role_ids BIGINT[],
    auto_approve_role_ids BIGINT[],
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 默认审批策略
INSERT INTO tool_approval_policy (tool_id, risk_level, approval_type, approver_role_ids) VALUES
(null, 'low',    'auto',   null),
(null, 'medium', 'single', ARRAY[1]),  -- admin 角色审批
(null, 'high',   'dual',   ARRAY[1]);  -- admin 角色双人审批

-- 索引
CREATE INDEX idx_approval_status ON approval_request(status);
CREATE INDEX idx_approval_requester ON approval_request(requester_id);
CREATE INDEX idx_approval_node_approver ON approval_node(approver_id, status);
