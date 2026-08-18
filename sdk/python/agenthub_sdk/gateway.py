from __future__ import annotations

import base64
import hashlib
import hmac
import json
import secrets
import time
from typing import Any
from urllib.request import Request, urlopen


def gateway_signature(secret: str, method: str, path: str, timestamp: int, nonce: str, body: bytes) -> str:
    digest = hashlib.sha256(body).hexdigest()
    canonical = f"{method.upper()}\n{path}\n{timestamp}\n{nonce}\n{digest}".encode()
    value = hmac.new(secret.encode(), canonical, hashlib.sha256).digest()
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


class GatewayClient:
    def __init__(self, base_url: str, public_key: str, secret: str, api_version: str = "v1"):
        self.base_url = base_url.rstrip("/")
        self.public_key = public_key
        self.secret = secret
        self.api_version = api_version

    def invoke(self, operation: str, input_data: Any) -> dict[str, Any]:
        path = f"/api/gateway/{self.api_version}/invoke"
        body = json.dumps({"operation": operation, "input": input_data}, separators=(",", ":")).encode()
        timestamp = int(time.time())
        nonce = secrets.token_urlsafe(18)
        request = Request(self.base_url + path, data=body, method="POST", headers={
            "Content-Type": "application/json",
            "X-Developer-Key": self.public_key,
            "X-Timestamp": str(timestamp),
            "X-Nonce": nonce,
            "X-Signature": gateway_signature(self.secret, "POST", path, timestamp, nonce, body),
        })
        with urlopen(request, timeout=30) as response:
            envelope = json.load(response)
        if envelope.get("code") != 200:
            raise RuntimeError(envelope.get("message", "Gateway request failed"))
        return envelope["data"]
