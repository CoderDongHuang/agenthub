"""文档分块器"""
import re
from typing import List


class DocumentChunker:
    """将文档分割为适合向量化的文本块"""

    def __init__(self, chunk_size: int = 500, chunk_overlap: int = 100):
        self.chunk_size = chunk_size
        self.chunk_overlap = chunk_overlap

    def chunk(self, text: str) -> List[str]:
        """按段落+句子分割文本"""
        if not text or not text.strip():
            return []

        # 先按段落分
        paragraphs = re.split(r'\n\s*\n', text)
        chunks = []

        for para in paragraphs:
            para = para.strip()
            if not para:
                continue
            if len(para) <= self.chunk_size:
                chunks.append(para)
            else:
                # 按句子进一步分割
                sentences = re.split(r'(?<=[。！？.!?])\s*', para)
                current = ""
                for sent in sentences:
                    if len(current) + len(sent) <= self.chunk_size:
                        current += sent
                    else:
                        if current:
                            chunks.append(current.strip())
                        current = sent
                if current:
                    chunks.append(current.strip())

        # 合并短块
        merged = []
        for chunk in chunks:
            if merged and len(merged[-1]) + len(chunk) < self.chunk_size:
                merged[-1] += "\n" + chunk
            else:
                merged.append(chunk)

        return merged[:100]  # 最多 100 个块
