-- V1: Phase 0 初始化 Schema，创建占位表确保 Flyway 正常运行
-- Phase 1 会替换为实际的用户/权限/Agent 表结构

CREATE TABLE IF NOT EXISTS flyway_placeholder (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 清理占位数据（Phase 1 会 DROP 此表）
TRUNCATE flyway_placeholder;
