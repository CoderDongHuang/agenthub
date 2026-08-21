"""PostgreSQL/pgvector-backed, tenant-isolated retrieval."""
import os
from typing import List, Tuple

import psycopg2
from pgvector.psycopg2 import register_vector

from agent_runtime.rag.embedding import embed_texts


class Retriever:
    def __init__(self, connection_factory=None):
        self._connection_factory = connection_factory or self._connect

    @staticmethod
    def _connect():
        connection = psycopg2.connect(
            host=os.getenv("DB_HOST", "localhost"), port=int(os.getenv("DB_PORT", "5432")),
            dbname=os.getenv("DB_NAME", "agenthub"), user=os.getenv("DB_USER", "agenthub"),
            password=os.getenv("DB_PASSWORD", "agenthub123"),
        )
        register_vector(connection)
        return connection

    def index(self, doc_id: str, chunks: List[str], tenant_id: str = "0", index_version: int = 1):
        embeddings = embed_texts(chunks)
        with self._connection_factory() as connection, connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM knowledge_chunk kc USING knowledge_document kd, knowledge_base kb "
                "WHERE kc.doc_id=kd.id AND kd.kb_id=kb.id AND kc.doc_id=%s AND kb.tenant_id=%s",
                (doc_id, tenant_id),
            )
            for index, (content, embedding) in enumerate(zip(chunks, embeddings)):
                cursor.execute(
                "INSERT INTO knowledge_chunk (doc_id, chunk_index, content, embedding, index_version, citation) "
                "VALUES (%s,%s,%s,%s,%s,jsonb_build_object('documentId',%s,'chunkIndex',%s))",
                (doc_id, index, content, embedding, index_version, doc_id, index),
                )
            cursor.execute(
                "UPDATE knowledge_document kd SET chunk_count=%s, status='ready' FROM knowledge_base kb "
                "WHERE kd.kb_id=kb.id AND kd.id=%s AND kb.tenant_id=%s",
                (len(chunks), doc_id, tenant_id),
            )

    def remove(self, doc_id: str, tenant_id: str = "0"):
        with self._connection_factory() as connection, connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM knowledge_chunk kc USING knowledge_document kd, knowledge_base kb "
                "WHERE kc.doc_id=kd.id AND kd.kb_id=kb.id AND kc.doc_id=%s AND kb.tenant_id=%s",
                (doc_id, tenant_id),
            )

    def search(self, query: str, top_k: int = 3, tenant_id: str = "0") -> List[Tuple[str, float]]:
        query_vector = embed_texts([query])[0]
        with self._connection_factory() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT kc.content, 1 - (kc.embedding <=> %s::vector) AS score "
                "FROM knowledge_chunk kc JOIN knowledge_document kd ON kd.id=kc.doc_id "
                "JOIN knowledge_base kb ON kb.id=kd.kb_id WHERE kb.tenant_id=%s "
                "AND kc.embedding IS NOT NULL ORDER BY kc.embedding <=> %s::vector LIMIT %s",
                (query_vector, tenant_id, query_vector, top_k),
            )
            return [(content, float(score)) for content, score in cursor.fetchall()]

    def get_context(self, query: str, top_k: int = 3, tenant_id: str = "0") -> str:
        return "\n\n".join(
            f"[Document {index}] (relevance: {score:.2f})\n{content}"
            for index, (content, score) in enumerate(self.search(query, top_k, tenant_id), 1) if score > 0.1
        )

    def stats(self, tenant_id: str | None = None) -> dict:
        with self._connection_factory() as connection, connection.cursor() as cursor:
            where = "WHERE kb.tenant_id=%s" if tenant_id is not None else ""
            cursor.execute(
                "SELECT COUNT(kc.id), COUNT(DISTINCT kc.doc_id) FROM knowledge_chunk kc "
                "JOIN knowledge_document kd ON kd.id=kc.doc_id JOIN knowledge_base kb ON kb.id=kd.kb_id " + where,
                (tenant_id,) if tenant_id is not None else (),
            )
            chunks, documents = cursor.fetchone()
            return {"total_chunks": chunks, "documents": documents}
