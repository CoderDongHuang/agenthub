import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from dingtalk_stream import AckMessage

from agent_runtime.channels.dingtalk_stream import AgentHubChatbotHandler


def test_stream_message_is_forwarded_to_internal_queue():
    response = MagicMock()
    response.raise_for_status.return_value = None
    client = AsyncMock()
    client.__aenter__.return_value = client
    client.post.return_value = response
    callback = SimpleNamespace(data={
        "robotCode": "ding-test",
        "msgId": "message-1",
        "senderStaffId": "user-1",
        "conversationId": "conversation-1",
        "conversationType": "1",
        "msgtype": "text",
        "text": {"content": "hello"},
        "sessionWebhook": "https://example.invalid/session",
    })

    handler = AgentHubChatbotHandler("http://java-console:8080", "x" * 32)
    with patch("agent_runtime.channels.dingtalk_stream.httpx.AsyncClient", return_value=client):
        status, message = asyncio.run(handler.process(callback))

    assert status == AckMessage.STATUS_OK
    assert message == "accepted"
    request = client.post.call_args
    assert request.args[0].endswith("/api/internal/channel/dingtalk-stream")
    assert request.kwargs["json"]["robotCode"] == "ding-test"
    assert request.kwargs["headers"]["X-Internal-Token"] == "x" * 32
