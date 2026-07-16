-- V2: 用户、角色、权限、部门表结构

-- 部门表
CREATE TABLE sys_department (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    parent_id BIGINT DEFAULT 0,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    sort_order INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 用户表
CREATE TABLE sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    email VARCHAR(100),
    phone VARCHAR(20),
    department_id BIGINT,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- 角色表
CREATE TABLE sys_role (
    id BIGSERIAL PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL,
    role_code VARCHAR(50) UNIQUE NOT NULL,
    description VARCHAR(200),
    tenant_id BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 权限表
CREATE TABLE sys_permission (
    id BIGSERIAL PRIMARY KEY,
    perm_name VARCHAR(100) NOT NULL,
    perm_code VARCHAR(100) UNIQUE NOT NULL,
    description VARCHAR(200)
);

-- 用户-角色关联表
CREATE TABLE sys_user_role (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    UNIQUE(user_id, role_id)
);

-- 角色-权限关联表
CREATE TABLE sys_role_permission (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL REFERENCES sys_role(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES sys_permission(id) ON DELETE CASCADE,
    UNIQUE(role_id, permission_id)
);

-- ========== 初始数据 ==========

-- 预设权限
INSERT INTO sys_permission (perm_name, perm_code, description) VALUES
('创建 Agent', 'agent:create', '创建新的 AI Agent'),
('编辑 Agent', 'agent:edit', '编辑已有 Agent 配置'),
('删除 Agent', 'agent:delete', '删除 Agent'),
('发布 Agent', 'agent:publish', '发布 Agent 上线'),
('管理用户', 'user:manage', '创建、编辑、禁用用户'),
('管理角色', 'role:manage', '管理角色与权限分配'),
('审批操作', 'approval:approve', '审批敏感工具调用'),
('管理工具', 'tool:manage', '注册和配置工具'),
('查看审计日志', 'audit:view', '查看操作审计日志'),
('管理预算', 'billing:manage', '设置预算和查看消费');

-- 预设角色
INSERT INTO sys_role (role_name, role_code, description, tenant_id) VALUES
('超级管理员', 'admin', '系统最高权限，管理所有功能', 0),
('Agent 创建者', 'agent_creator', '可以创建和管理自己的 Agent', 0),
('Agent 使用者', 'agent_user', '可以与 Agent 对话', 0),
('审批人', 'approver', '审批敏感操作', 0);

-- 绑定角色权限
-- admin: 所有权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.role_code = 'admin';

-- agent_creator: 创建/编辑/发布 Agent
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.role_code = 'agent_creator' AND p.perm_code IN ('agent:create', 'agent:edit', 'agent:publish');

-- agent_user: 只有基本使用权限（无需额外权限，登录即可使用）
-- approver: 审批权限
INSERT INTO sys_role_permission (role_id, permission_id)
SELECT r.id, p.id FROM sys_role r, sys_permission p
WHERE r.role_code = 'approver' AND p.perm_code IN ('approval:approve', 'audit:view');

-- 预设管理员用户（密码: admin123，BCrypt 加密）
INSERT INTO sys_user (username, password_hash, display_name, email, tenant_id, status) VALUES
('admin', '$2b$10$p2NxIPeR0567yLFcnwZ8Su5u7hxXovrXtcoHYPp6GTiCqGjFyquzm', '系统管理员', 'admin@agenthub.local', 0, 'active');

-- 绑定 admin 角色
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r
WHERE u.username = 'admin' AND r.role_code = 'admin';
