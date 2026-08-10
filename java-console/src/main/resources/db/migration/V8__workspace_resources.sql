-- V8: persistent workflow, guardrail, channel and routing configurations

CREATE TABLE workspace_resource (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    resource_type VARCHAR(30) NOT NULL,
    resource_key VARCHAR(100) NOT NULL,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(20) NOT NULL DEFAULT 'draft',
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, resource_type, resource_key)
);

CREATE TABLE workspace_execution (
    id BIGSERIAL PRIMARY KEY,
    resource_id BIGINT NOT NULL REFERENCES workspace_resource(id) ON DELETE CASCADE,
    execution_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    result JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by BIGINT,
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP
);

CREATE INDEX idx_workspace_resource_type ON workspace_resource(tenant_id, resource_type, updated_at DESC);
CREATE INDEX idx_workspace_execution_resource ON workspace_execution(resource_id, started_at DESC);

INSERT INTO workspace_resource (resource_type, resource_key, name, description, status, config) VALUES
('workflow', 'claim-collaboration', '理赔协同流程', '材料识别、条款分析、人工复核和结果交付。', 'draft',
 '{"version":1,"nodes":[{"id":1,"type":"entry","title":"客户请求","detail":"Web 对话入口","x":40,"y":135},{"id":2,"type":"agent","title":"理赔材料助手","detail":"读取材料并判断意图","x":250,"y":60},{"id":3,"type":"approval","title":"金额复核","detail":"超过 5000 元时触发","x":475,"y":60},{"id":4,"type":"tool","title":"赔付系统","detail":"refund.execute","x":700,"y":60},{"id":5,"type":"agent","title":"结果解释","detail":"生成清晰的客户答复","x":475,"y":220},{"id":6,"type":"output","title":"返回客户","detail":"记录结果与审计","x":700,"y":220}]}'::jsonb),
('guardrail', 'pii', '隐私信息脱敏', '手机号、身份证、邮箱与银行卡。', 'published', '{"enabled":true,"color":"sage"}'::jsonb),
('guardrail', 'prompt', '提示词攻击检测', '识别越狱、角色劫持与指令泄露。', 'published', '{"enabled":true,"color":"blue"}'::jsonb),
('guardrail', 'topic', '业务话题边界', '阻止回答超出 Agent 职责的问题。', 'published', '{"enabled":true,"color":"amber"}'::jsonb),
('guardrail', 'quality', '输出质量门槛', '事实性、完整性和引用检查。', 'draft', '{"enabled":false,"color":"coral"}'::jsonb),
('channel', 'web', '网页组件', '嵌入官网或业务系统。', 'active', '{"enabled":true,"agentId":1,"timeoutSeconds":30,"retryCount":2,"membersOnly":true,"saveConversation":true}'::jsonb),
('channel', 'wechat', '企业微信', '企业内部应用与客户群。', 'inactive', '{"enabled":false,"agentId":1,"webhookUrl":"","token":"","encodingAesKey":""}'::jsonb),
('channel', 'dingtalk', '钉钉', '机器人和工作通知。', 'inactive', '{"enabled":false,"agentId":1,"webhookUrl":"","secret":""}'::jsonb),
('channel', 'feishu', '飞书', '应用机器人与消息卡片。', 'inactive', '{"enabled":false,"agentId":1,"webhookUrl":"","secret":""}'::jsonb),
('channel', 'api', '开放 API', 'REST 与 SSE 调用。', 'active', '{"enabled":true,"agentId":1,"timeoutSeconds":30,"retryCount":2}'::jsonb)
ON CONFLICT DO NOTHING;
