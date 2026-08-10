INSERT INTO knowledge_base (id, tenant_id, name, description, embedding_model)
VALUES (1, 0, '默认知识库', '工作区默认知识资产集合', 'text-embedding-3-small')
ON CONFLICT (id) DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('knowledge_base', 'id'),
    GREATEST((SELECT COALESCE(MAX(id), 1) FROM knowledge_base), 1),
    true
);
