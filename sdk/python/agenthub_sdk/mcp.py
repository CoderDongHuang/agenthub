from __future__ import annotations

import json
from itertools import count
from typing import Any
from urllib.request import Request, urlopen


class McpClient:
    def __init__(self, endpoint: str, internal_token: str):
        self.endpoint = endpoint
        self.internal_token = internal_token
        self._ids = count(1)

    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        body = json.dumps({
            "jsonrpc": "2.0", "id": next(self._ids), "method": method, "params": params or {}
        }, separators=(",", ":")).encode()
        request = Request(self.endpoint, data=body, method="POST", headers={
            "Content-Type": "application/json", "X-Internal-Token": self.internal_token
        })
        with urlopen(request, timeout=30) as response:
            envelope = json.load(response)
        if "error" in envelope:
            raise RuntimeError(envelope["error"]["message"])
        return envelope["result"]

    def initialize(self) -> dict[str, Any]:
        return self.request("initialize", {
            "protocolVersion": "2025-03-26", "capabilities": {},
            "clientInfo": {"name": "agenthub-sdk", "version": "0.1.0"},
        })

    def list_tools(self) -> list[dict[str, Any]]:
        return self.request("tools/list")["tools"]

    def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        return self.request("tools/call", {"name": name, "arguments": arguments})
