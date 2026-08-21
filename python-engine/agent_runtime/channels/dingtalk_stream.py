"""Authenticated DingTalk Stream bridge into the Java channel queue."""

import logging
import os

import httpx
from dingtalk_stream import AckMessage, ChatbotHandler, ChatbotMessage, Credential, DingTalkStreamClient

log = logging.getLogger(__name__)


class AgentHubChatbotHandler(ChatbotHandler):
    def __init__(self, java_console_url: str, internal_token: str):
        super().__init__()
        self.java_console_url = java_console_url.rstrip("/")
        self.internal_token = internal_token

    async def process(self, callback):
        payload = ChatbotMessage.from_dict(callback.data).to_dict()
        try:
            async with httpx.AsyncClient(timeout=10) as client:
                response = await client.post(
                    f"{self.java_console_url}/api/internal/channel/dingtalk-stream",
                    headers={"X-Internal-Token": self.internal_token, "X-Tenant-Id": "0"},
                    json=payload,
                )
                response.raise_for_status()
            return AckMessage.STATUS_OK, "accepted"
        except Exception as exception:
            log.exception("Unable to enqueue DingTalk Stream message: %s", exception)
            return AckMessage.STATUS_SYSTEM_EXCEPTION, "enqueue failed"


def create_dingtalk_stream_client() -> DingTalkStreamClient | None:
    client_id = os.getenv("DINGTALK_CLIENT_ID", "").strip()
    client_secret = os.getenv("DINGTALK_CLIENT_SECRET", "").strip()
    internal_token = os.getenv("AGENTHUB_INTERNAL_TOKEN", "").strip()
    if not client_id and not client_secret:
        return None
    if not client_id or not client_secret:
        raise RuntimeError("DINGTALK_CLIENT_ID and DINGTALK_CLIENT_SECRET must be configured together")
    if len(internal_token) < 32:
        raise RuntimeError("AGENTHUB_INTERNAL_TOKEN must contain at least 32 characters")
    client = DingTalkStreamClient(Credential(client_id, client_secret))
    client.register_callback_handler(
        ChatbotMessage.TOPIC,
        AgentHubChatbotHandler(os.getenv("JAVA_CONSOLE_URL", "http://localhost:8080"), internal_token),
    )
    return client
