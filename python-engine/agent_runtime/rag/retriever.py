"""检索器 — 向量相似度检索"""
import logging
from typing import List, Tuple

from agent_runtime.rag.embedding import embed_texts, cosine_similarity

log = logging.getLogger(__name__)


class Retriever:
    """知识库检索器"""

    def __init__(self):
        self._store: List[dict] = []  # [{id, content, embedding}]

    def index(self, doc_id: str, chunks: List[str], tenant_id: str = "0"):
        """将文档块索引到向量存储"""
        if not chunks:
            return
        self.remove(doc_id, tenant_id)
        embeddings = embed_texts(chunks)
        for i, (chunk, emb) in enumerate(zip(chunks, embeddings)):
            self._store.append({
                "id": f"{doc_id}_{i}",
                "doc_id": doc_id,
                "tenant_id": str(tenant_id),
                "content": chunk,
                "embedding": emb,
            })
        log.info(f"Indexed {len(chunks)} chunks from doc {doc_id}")

    def remove(self, doc_id: str, tenant_id: str = "0"):
        """移除某个文档的全部内存索引。"""
        self._store = [item for item in self._store if not (
            item["doc_id"] == str(doc_id) and item["tenant_id"] == str(tenant_id)
        )]

    def search(self, query: str, top_k: int = 3, tenant_id: str = "0") -> List[Tuple[str, float]]:
        """搜索最相关的 top_k 文本块"""
        if not self._store:
            return []

        query_vec = embed_texts([query])[0]
        scored = []
        for item in self._store:
            if item["tenant_id"] != str(tenant_id):
                continue
            score = cosine_similarity(query_vec, item["embedding"])
            scored.append((item["content"], score))

        scored.sort(key=lambda x: x[1], reverse=True)
        return scored[:top_k]

    def get_context(self, query: str, top_k: int = 3, tenant_id: str = "0") -> str:
        """获取检索上下文（直接注入 LLM）"""
        results = self.search(query, top_k, tenant_id)
        if not results:
            return ""

        context_parts = []
        for i, (content, score) in enumerate(results):
            if score > 0.1:
                context_parts.append(f"[Document {i+1}] (relevance: {score:.2f})\n{content}")

        return "\n\n".join(context_parts)

    def stats(self, tenant_id: str | None = None) -> dict:
        items = self._store if tenant_id is None else [
            item for item in self._store if item["tenant_id"] == str(tenant_id)
        ]
        return {
            "total_chunks": len(items),
            "documents": len({item["doc_id"] for item in items}),
        }
