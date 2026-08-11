"""Redis-backed, tenant-isolated conversation state."""
import json
import os
from datetime import datetime, timezone
from typing import Any, Dict

from redis.asyncio import Redis


class SessionManager:
    def __init__(self, redis_client: Redis | None = None):
        self._redis = redis_client or Redis.from_url(
            os.getenv("REDIS_URL", "redis://localhost:6380/0"), decode_responses=True
        )
        self._ttl = int(os.getenv("SESSION_TTL_SECONDS", "86400"))
        self._max_messages = int(os.getenv("SESSION_MAX_MESSAGES", "100"))

    @staticmethod
    def _key(tenant_id: str, session_id: str) -> str:
        if not tenant_id or not session_id:
            raise ValueError("tenant_id and session_id are required")
        return f"agenthub:session:{tenant_id}:{session_id}"

    async def get_or_create(self, session_id: str, tenant_id: str = "0") -> Dict[str, Any]:
        key = self._key(tenant_id, session_id)
        raw = await self._redis.get(key)
        if raw:
            await self._redis.expire(key, self._ttl)
            return json.loads(raw)
        return {"session_id": session_id, "tenant_id": tenant_id, "messages": [],
                "created_at": datetime.now(timezone.utc).isoformat()}

    async def save(self, session_id: str, data: Dict[str, Any], tenant_id: str = "0"):
        data["tenant_id"] = tenant_id
        data["messages"] = data.get("messages", [])[-self._max_messages:]
        key = self._key(tenant_id, session_id)
        async with self._redis.pipeline(transaction=True) as pipe:
            pipe.set(key, json.dumps(data, ensure_ascii=False), ex=self._ttl)
            pipe.sadd(f"agenthub:sessions:{tenant_id}", session_id)
            pipe.expire(f"agenthub:sessions:{tenant_id}", self._ttl)
            await pipe.execute()

    async def delete(self, session_id: str, tenant_id: str = "0"):
        await self._redis.delete(self._key(tenant_id, session_id))
        await self._redis.srem(f"agenthub:sessions:{tenant_id}", session_id)

    async def list_sessions(self, tenant_id: str = "0") -> list[str]:
        return sorted(await self._redis.smembers(f"agenthub:sessions:{tenant_id}"))

    async def close(self):
        await self._redis.aclose()
