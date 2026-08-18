# AgentHub Python SDK

This dependency-free SDK covers three local-first integration surfaces:

- `ToolPackage`: builds a signed-package registration request for the private registry.
- `GatewayClient`: signs API Gateway requests with HMAC-SHA256, timestamp and nonce headers.
- `McpClient`: calls the AgentHub MCP Streamable HTTP JSON-RPC endpoint.

The package signing root stays on the AgentHub server. The SDK computes the artifact digest and uploads the artifact plus manifest; the server returns and stores the tenant signature.

```powershell
python -m pip install -e sdk/python
```

```python
from agenthub_sdk import GatewayClient

client = GatewayClient(
    "http://127.0.0.1:8080",
    public_key="dev_...",
    secret="devsec_...",
)
result = client.invoke("agent.chat", {
    "agentId": 1,
    "message": "Summarize the current incident.",
})
print(result["output"]["reply"])
```

The developer secret is shown once when an application is created. Keep it outside source control and enable only the operations that application needs.
