"""
工具注册中心 — 内置工具 + 自定义工具自动发现 + 同步到 Java
"""
import importlib
import inspect
import logging
import os
import pkgutil
from typing import List

from agent_runtime.tools.base import AgentTool

log = logging.getLogger(__name__)


class ToolRegistry:
    """工具注册中心"""

    def __init__(self):
        self._tools: dict[str, AgentTool] = {}

    def register(self, tool: AgentTool):
        self._tools[tool.name] = tool
        log.info(f"Tool registered: {tool.name} (risk={tool.risk_level})")

    def get(self, name: str) -> AgentTool | None:
        return self._tools.get(name)

    def list_all(self) -> List[AgentTool]:
        return list(self._tools.values())

    def auto_discover(self, package_path: str = "agent_runtime.tools.custom"):
        """
        自动扫描 custom/ 目录，发现 @tool 装饰的类并注册。
        开发者只需把 Python 文件放到 tools/custom/ 下即可。
        """
        try:
            package = importlib.import_module(package_path)
            pkg_dir = os.path.dirname(package.__file__) if package.__file__ else None
            if not pkg_dir:
                return

            for _, module_name, _ in pkgutil.iter_modules([pkg_dir]):
                if module_name.startswith("_"):
                    continue
                try:
                    full_name = f"{package_path}.{module_name}"
                    module = importlib.import_module(full_name)
                    # 查找 AgentTool 的子类
                    for name, obj in inspect.getmembers(module, inspect.isclass):
                        if issubclass(obj, AgentTool) and obj is not AgentTool:
                            if not hasattr(obj, "name") or not obj.name:
                                continue
                            instance = obj()
                            self.register(instance)
                            log.info(f"Auto-discovered custom tool: {instance.name} from {full_name}")
                except Exception as e:
                    log.warning(f"Failed to load custom tool module {module_name}: {e}")
        except ModuleNotFoundError:
            log.info("No custom tools directory found, skipping auto-discovery")

    async def sync_to_java(self):
        """将已注册的工具同步到 Java Console"""
        import httpx
        internal_token = os.getenv("AGENTHUB_INTERNAL_TOKEN", "")
        if len(internal_token) < 32:
            raise RuntimeError("AGENTHUB_INTERNAL_TOKEN must contain at least 32 characters")
        java_console_url = os.getenv("JAVA_CONSOLE_URL", "http://localhost:8080").rstrip("/")
        failures = []
        synced = 0
        async with httpx.AsyncClient(timeout=10) as client:
            for tool in self.list_all():
                try:
                    resp = await client.post(
                        f"{java_console_url}/api/tools/register",
                        headers={"X-Internal-Token": internal_token, "X-Tenant-Id": "0"},
                        json={
                            "toolName": tool.name,
                            "toolCode": tool.name,
                            "description": tool.description,
                            "category": tool.category,
                            "riskLevel": tool.risk_level,
                            "rateLimit": tool.rate_limit,
                            "timeoutSeconds": tool.timeout,
                            "jsonSchema": self._tool_to_schema(tool),
                        }
                    )
                    if resp.status_code == 200:
                        synced += 1
                        log.info(f"Synced tool to Java: {tool.name}")
                    else:
                        failures.append(tool.name)
                        log.warning(f"Failed to sync tool {tool.name}: {resp.status_code}")
                except Exception as e:
                    failures.append(tool.name)
                    log.warning(f"Failed to sync tool {tool.name}: {e}")
        if failures:
            raise RuntimeError(f"Failed to sync {len(failures)} tool(s): {', '.join(failures)}")
        return synced

    def _tool_to_schema(self, tool: AgentTool) -> dict:
        """从工具生成 JSON Schema"""
        sig = inspect.signature(tool.execute)
        props = {}
        required = []
        for pname, param in sig.parameters.items():
            if pname == "self":
                continue
            ptype = "string"
            if param.annotation is int:
                ptype = "integer"
            elif param.annotation is float:
                ptype = "number"
            props[pname] = {"type": ptype}
            if param.default is inspect.Parameter.empty:
                required.append(pname)
        return {"type": "object", "properties": props, "required": required}
