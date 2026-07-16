"""
会话管理器 — 管理多轮对话上下文
Phase 1: 内存存储，后续接入 Redis
"""
import logging
from typing import Any, Dict

log = logging.getLogger(__name__)


class SessionManager:
    """会话管理器（Phase 1: 内存存储）"""

    def __init__(self):
        self._sessions: Dict[str, Dict[str, Any]] = {}

    async def get_or_create(self, session_id: str) -> Dict[str, Any]:
        """获取或创建会话"""
        if session_id not in self._sessions:
            self._sessions[session_id] = {
                "session_id": session_id,
                "messages": [],
                "created_at": None,
            }
            log.info(f"创建新会话: {session_id}")
        return self._sessions[session_id]

    async def save(self, session_id: str, data: Dict[str, Any]):
        """保存会话"""
        self._sessions[session_id] = data

    async def delete(self, session_id: str):
        """删除会话"""
        self._sessions.pop(session_id, None)
        log.info(f"会话已删除: {session_id}")

    async def list_sessions(self) -> list:
        """列出所有会话 ID"""
        return list(self._sessions.keys())
