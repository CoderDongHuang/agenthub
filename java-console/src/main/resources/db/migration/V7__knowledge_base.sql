-- V7: 知识库 + 文档表

CREATE TABLE knowledge_base (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    embedding_model VARCHAR(50) DEFAULT 'text-embedding-3-small',
    created_by BIGINT REFERENCES sys_user(id),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE knowledge_document (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT REFERENCES knowledge_base(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    file_type VARCHAR(20),
    file_size BIGINT DEFAULT 0,
    chunk_count INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'processing',
    content TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

-- 创建 pgvector 扩展（需要 pgvector 插件）
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_chunk (
    id BIGSERIAL PRIMARY KEY,
    doc_id BIGINT REFERENCES knowledge_document(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1536),
    created_at TIMESTAMP DEFAULT NOW()
);

-- 索引
CREATE INDEX idx_chunk_doc ON knowledge_chunk(doc_id);
CREATE INDEX idx_doc_kb ON knowledge_document(kb_id);
