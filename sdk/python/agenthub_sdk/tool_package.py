from __future__ import annotations

import base64
import hashlib
import re
from dataclasses import dataclass, field
from typing import Any

SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?$")


@dataclass(frozen=True)
class ToolPackage:
    package_name: str
    version: str
    artifact: bytes = field(repr=False)
    manifest: dict[str, Any]
    source_uri: str
    package_type: str = "tool"
    visibility: str = "private"
    compatibility: dict[str, Any] = field(default_factory=lambda: {"minPlatformVersion": "0.1.0"})

    def __post_init__(self) -> None:
        if not self.package_name or len(self.package_name) > 160:
            raise ValueError("package_name is required and must not exceed 160 characters")
        if not SEMVER.fullmatch(self.version):
            raise ValueError("version must use semantic versioning")
        if not self.artifact:
            raise ValueError("artifact is required")
        if self.package_type not in {"tool", "plugin", "mcp"}:
            raise ValueError("package_type is invalid")
        if self.visibility not in {"private", "tenant", "public"}:
            raise ValueError("visibility is invalid")

    @property
    def digest(self) -> str:
        return hashlib.sha256(self.artifact).hexdigest()

    def registration(self) -> dict[str, Any]:
        return {
            "packageName": self.package_name,
            "version": self.version,
            "packageType": self.package_type,
            "visibility": self.visibility,
            "sourceUri": self.source_uri,
            "artifactBase64": base64.b64encode(self.artifact).decode(),
            "manifest": self.manifest,
            "compatibility": self.compatibility,
        }
