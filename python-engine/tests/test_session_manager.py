import asyncio
import json

from agent_runtime.core.session_manager import SessionManager


class FakeRedis:
    def __init__(self):
        self.data = {}

    async def eval(self, _script, _keys, key, index_key, session_id, tenant_id,
                   created_at, messages_json, maximum, _ttl):
        session = json.loads(self.data.get(key) or json.dumps({
            "session_id": session_id,
            "tenant_id": tenant_id,
            "messages": [],
            "created_at": created_at,
        }))
        session["messages"].extend(json.loads(messages_json))
        session["messages"] = session["messages"][-int(maximum):]
        self.data[key] = json.dumps(session)
        self.data.setdefault(index_key, set()).add(session_id)
        return len(session["messages"])


def test_append_messages_keeps_concurrent_updates(monkeypatch):
    redis = FakeRedis()
    manager = SessionManager(redis)
    monkeypatch.setattr(manager, "_max_messages", 10)

    async def run():
        await asyncio.gather(
            manager.append_messages("session-1", [{"role": "user", "content": "one"}], "tenant-1"),
            manager.append_messages("session-1", [{"role": "user", "content": "two"}], "tenant-1"),
        )

    asyncio.run(run())
    saved = json.loads(redis.data["agenthub:session:tenant-1:session-1"])
    assert {item["content"] for item in saved["messages"]} == {"one", "two"}
