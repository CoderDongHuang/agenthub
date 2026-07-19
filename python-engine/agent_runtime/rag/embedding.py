"""向量化服务 — TF-IDF 简易模式 + OpenAI embedding 可选"""
import hashlib
import logging
import os
import re
from typing import List

log = logging.getLogger(__name__)

# 用 OpenAI 做 embedding（如果有 Key）
_use_openai = bool(os.getenv("OPENAI_API_KEY"))
_openai_client = None

if _use_openai:
    try:
        from openai import OpenAI
        _openai_client = OpenAI()
        log.info("Using OpenAI embeddings")
    except Exception:
        _use_openai = False


def embed_texts(texts: List[str]) -> List[List[float]]:
    """将文本列表转为向量列表"""
    if _use_openai and _openai_client:
        try:
            resp = _openai_client.embeddings.create(
                model="text-embedding-3-small",
                input=[t[:8000] for t in texts],
            )
            return [d.embedding for d in resp.data]
        except Exception as e:
            log.warning(f"OpenAI embedding failed: {e}, falling back to TF-IDF")

    return [_tfidf_vector(t) for t in texts]


def _tfidf_vector(text: str, dims: int = 128) -> List[float]:
    """简易 TF-IDF 风格向量 (无需外部 API)"""
    # 分词
    words = re.findall(r'[a-zA-Z0-9一-鿿]+', text.lower())
    if not words:
        return [0.0] * dims

    # 词频
    tf = {}
    for w in words:
        tf[w] = tf.get(w, 0) + 1

    # 用 hash 映射到固定维度
    vector = [0.0] * dims
    for word, count in tf.items():
        h = int(hashlib.md5(word.encode()).hexdigest(), 16)
        idx = h % dims
        vector[idx] += count / len(words)

    # 归一化
    norm = sum(v * v for v in vector) ** 0.5
    if norm > 0:
        vector = [v / norm for v in vector]
    return vector


def cosine_similarity(a: List[float], b: List[float]) -> float:
    """余弦相似度"""
    dot = sum(x * y for x, y in zip(a, b))
    norma = sum(x * x for x in a) ** 0.5
    normb = sum(y * y for y in b) ** 0.5
    if norma == 0 or normb == 0:
        return 0.0
    return dot / (norma * normb)
