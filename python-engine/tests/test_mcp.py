"""MCP protocol and sandbox integration tests."""
import asyncio
import os
import sys

import httpx

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent_runtime.core.tool_executor import ToolExecutor
from agent_runtime.mcp.protocol import MCP_PROTOCOL_VERSION, McpClient, McpProtocolServer


def test_mcp_initialize_list_and_call_round_trip():
    server = McpProtocolServer(ToolExecutor())

    async def run():
        initialized = await server.handle({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": MCP_PROTOCOL_VERSION},
        })
        listed = await server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        called = await server.handle({
            "jsonrpc": "2.0", "id": 3, "method": "tools/call",
            "params": {"name": "calculator", "arguments": {"expression": "6*7"}},
        })
        return initialized, listed, called

    initialized, listed, called = asyncio.run(run())
    assert initialized["result"]["protocolVersion"] == MCP_PROTOCOL_VERSION
    assert any(tool["name"] == "calculator" for tool in listed["result"]["tools"])
    assert called["result"]["content"][0]["text"] == "42"
    assert called["result"]["isError"] is False


def test_mcp_unknown_method_uses_json_rpc_error():
    response = asyncio.run(McpProtocolServer(ToolExecutor()).handle(
        {"jsonrpc": "2.0", "id": "x", "method": "unknown", "params": {}}
    ))
    assert response["error"]["code"] == -32601


def test_mcp_tool_call_is_blocked_by_sandbox():
    response = asyncio.run(McpProtocolServer(ToolExecutor()).handle({
        "jsonrpc": "2.0", "id": 4, "method": "tools/call",
        "params": {"name": "web_search", "arguments": {"query": "https://127.0.0.1/admin"}},
    }))
    assert response["result"]["isError"] is True
    assert "sandbox policy blocked" in response["result"]["content"][0]["text"].lower()


def test_http_mcp_client_round_trip_against_asgi_endpoint(monkeypatch):
    token = "test-internal-token-at-least-32-characters"
    monkeypatch.setenv("AGENTHUB_INTERNAL_TOKEN", token)
    import main

    main.INTERNAL_API_TOKEN = token
    transport = httpx.ASGITransport(app=main.app)
    client = McpClient("http://test/mcp", token, transport=transport)

    async def run():
        initialized = await client.initialize()
        tools = await client.list_tools()
        called = await client.call_tool("calculator", {"expression": "9+10"})
        return initialized, tools, called

    initialized, tools, called = asyncio.run(run())
    assert initialized["protocolVersion"] == MCP_PROTOCOL_VERSION
    assert any(tool["name"] == "calculator" for tool in tools)
    assert called["content"][0]["text"] == "19"
