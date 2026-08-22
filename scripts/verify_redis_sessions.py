"""Live Redis verification for atomic, tenant-isolated session appends."""

import asyncio
import sys
import uuid
from pathlib import Path

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python-engine"))
load_dotenv(ROOT / ".env")

from agent_runtime.core.session_manager import SessionManager


async def main() -> None:
    manager = SessionManager()
    session_id = f"live-redis-{uuid.uuid4()}"
    try:
        await asyncio.gather(
            manager.append_messages(session_id, [{"role": "user", "content": "one"}], "e2e"),
            manager.append_messages(session_id, [{"role": "user", "content": "two"}], "e2e"),
        )
        session = await manager.get_or_create(session_id, "e2e")
        contents = sorted(message["content"] for message in session["messages"])
        if contents != ["one", "two"]:
            raise RuntimeError(f"Atomic append verification failed: {contents}")
        print("PASS: real Redis preserved both concurrent session updates")
    finally:
        await manager.delete(session_id, "e2e")
        await manager.close()


if __name__ == "__main__":
    asyncio.run(main())
