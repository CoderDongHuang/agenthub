"""Deterministic pre-execution policy for tool and MCP calls."""
from __future__ import annotations

import ipaddress
import json
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse


class SandboxViolation(ValueError):
    pass


@dataclass(frozen=True)
class SandboxLimits:
    max_depth: int = 8
    max_items: int = 1000
    max_serialized_bytes: int = 64 * 1024
    max_string_length: int = 16 * 1024


class ToolSandboxPolicy:
    """Fail-closed validation used before in-process and isolated-worker execution."""

    def __init__(self, limits: SandboxLimits | None = None):
        self.limits = limits or SandboxLimits()

    def validate(self, tool_name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if not tool_name or len(tool_name) > 120:
            raise SandboxViolation("tool name is invalid")
        if not isinstance(arguments, dict):
            raise SandboxViolation("tool arguments must be an object")
        encoded = json.dumps(arguments, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(encoded) > self.limits.max_serialized_bytes:
            raise SandboxViolation("tool arguments exceed the 64 KiB limit")
        self._walk(arguments, depth=0, item_count=[0])
        return arguments

    def _walk(self, value: Any, depth: int, item_count: list[int]) -> None:
        if depth > self.limits.max_depth:
            raise SandboxViolation("tool arguments exceed the nesting limit")
        if isinstance(value, dict):
            for key, nested in value.items():
                item_count[0] += 1
                if item_count[0] > self.limits.max_items:
                    raise SandboxViolation("tool arguments contain too many items")
                key_text = str(key)
                if key_text.startswith("__") or "\x00" in key_text:
                    raise SandboxViolation("tool arguments contain a forbidden key")
                self._walk(nested, depth + 1, item_count)
        elif isinstance(value, (list, tuple)):
            for nested in value:
                item_count[0] += 1
                if item_count[0] > self.limits.max_items:
                    raise SandboxViolation("tool arguments contain too many items")
                self._walk(nested, depth + 1, item_count)
        elif isinstance(value, str):
            if len(value) > self.limits.max_string_length:
                raise SandboxViolation("tool argument string is too long")
            if "\x00" in value or self._has_path_traversal(value):
                raise SandboxViolation("tool arguments contain path traversal or null bytes")
            self._validate_url(value)

    def _has_path_traversal(self, value: str) -> bool:
        normalized = value.replace("\\", "/")
        return any(part == ".." for part in normalized.split("/"))

    def _validate_url(self, value: str) -> None:
        if not value.lower().startswith(("http://", "https://")):
            return
        parsed = urlparse(value)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise SandboxViolation("tool URL must be public HTTPS without user information")
        host = parsed.hostname.lower()
        if host in {"localhost", "localhost.localdomain"} or host.endswith(".local"):
            raise SandboxViolation("tool URL cannot target a local host")
        try:
            address = ipaddress.ip_address(host)
        except ValueError:
            return
        if not address.is_global:
            raise SandboxViolation("tool URL cannot target a non-public address")
