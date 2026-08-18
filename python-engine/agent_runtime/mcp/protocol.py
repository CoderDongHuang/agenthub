"""MCP JSON-RPC 2.0 server/client bridge for the local tool registry."""
from __future__ import annotations

import itertools
from typing import Any

import httpx

from agent_runtime.core.tool_executor import ToolExecutor

MCP_PROTOCOL_VERSION = "2025-03-26"


class McpProtocolServer:
    def __init__(self, executor: ToolExecutor):
        self.executor = executor

    async def handle(self, request: dict[str, Any]) -> dict[str, Any] | None:
        request_id = request.get("id")
        if request.get("jsonrpc") != "2.0" or not isinstance(request.get("method"), str):
            return self._error(request_id, -32600, "Invalid Request")
        method = request["method"]
        params = request.get("params") or {}
        try:
            if method == "initialize":
                requested = str(params.get("protocolVersion", MCP_PROTOCOL_VERSION))
                return self._result(request_id, {
                    "protocolVersion": MCP_PROTOCOL_VERSION if requested else MCP_PROTOCOL_VERSION,
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": "agenthub-runtime", "version": "0.1.0"},
                    "instructions": "Tools are executed through AgentHub sandbox, timeout and risk policies.",
                })
            if method == "notifications/initialized":
                return None
            if method == "ping":
                return self._result(request_id, {})
            if method == "tools/list":
                tools = []
                for schema in self.executor.get_json_schemas():
                    function = schema["function"]
                    tools.append({
                        "name": function["name"],
                        "description": function["description"],
                        "inputSchema": function["parameters"],
                    })
                return self._result(request_id, {"tools": tools})
            if method == "tools/call":
                if not isinstance(params, dict) or not isinstance(params.get("name"), str):
                    return self._error(request_id, -32602, "tools/call requires a tool name")
                arguments = params.get("arguments") or {}
                if not isinstance(arguments, dict):
                    return self._error(request_id, -32602, "tools/call arguments must be an object")
                output = await self.executor.execute(params["name"], arguments)
                is_error = output.lower().startswith("error:")
                return self._result(request_id, {
                    "content": [{"type": "text", "text": output}],
                    "isError": is_error,
                })
            return self._error(request_id, -32601, f"Method not found: {method}")
        except Exception as exc:
            return self._error(request_id, -32603, f"Internal error: {type(exc).__name__}")

    def _result(self, request_id: Any, result: dict[str, Any]) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "result": result}

    def _error(self, request_id: Any, code: int, message: str) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


class McpClient:
    def __init__(self, endpoint: str, internal_token: str, transport: httpx.AsyncBaseTransport | None = None):
        self.endpoint = endpoint
        self.internal_token = internal_token
        self.transport = transport
        self._ids = itertools.count(1)

    async def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        payload = {"jsonrpc": "2.0", "id": next(self._ids), "method": method, "params": params or {}}
        async with httpx.AsyncClient(transport=self.transport, timeout=10) as client:
            response = await client.post(self.endpoint, json=payload,
                                         headers={"X-Internal-Token": self.internal_token})
            response.raise_for_status()
            result = response.json()
        if "error" in result:
            raise RuntimeError(result["error"]["message"])
        return result["result"]

    async def initialize(self) -> dict[str, Any]:
        return await self.request("initialize", {
            "protocolVersion": MCP_PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "agenthub-mcp-client", "version": "0.1.0"},
        })

    async def list_tools(self) -> list[dict[str, Any]]:
        return (await self.request("tools/list"))["tools"]

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        return await self.request("tools/call", {"name": name, "arguments": arguments})
