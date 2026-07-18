"""
工具执行器 — 管理工具的注册、查找、执行、JSON Schema 生成
"""
import asyncio
import inspect
import logging
import time
from typing import Any, Dict, List, Optional

from agent_runtime.tools.base import AgentTool
from agent_runtime.tools.builtin.calculator import CalculatorTool
from agent_runtime.tools.builtin.datetime_tool import DateTimeTool
from agent_runtime.tools.builtin.web_search import WebSearchTool
from agent_runtime.tools.registry import ToolRegistry

log = logging.getLogger(__name__)


class ToolExecutor:
    """工具执行器（含沙箱：超时/异常隔离/限流）"""

    def __init__(self):
        self._tools: Dict[str, AgentTool] = {}
        self._call_counts: Dict[str, int] = {}
        self._registry = ToolRegistry()
        self._register_builtin_tools()
        self._discover_custom_tools()

    def _register_builtin_tools(self):
        """注册内置工具"""
        for tool_cls in [CalculatorTool, DateTimeTool, WebSearchTool]:
            instance = tool_cls()
            self.register(instance)

    def _discover_custom_tools(self):
        """自动发现 custom/ 目录下的自定义工具"""
        self._registry.auto_discover()
        for tool in self._registry.list_all():
            if tool.name not in self._tools:
                self.register(tool)

    async def sync_to_java(self):
        """同步所有工具到 Java Console"""
        await self._registry.sync_to_java()
        # Also sync built-in tools not yet in registry
        for tool in self.list_tools():
            if not self._registry.get(tool.name):
                self._registry.register(tool)
        await self._registry.sync_to_java()

    def register(self, tool: AgentTool):
        """注册一个工具"""
        name = tool.name
        self._tools[name] = tool
        log.info(f"工具已注册: {name} (risk={tool.risk_level})")

    def get_tool(self, name: str) -> Optional[AgentTool]:
        """按名称查找工具"""
        return self._tools.get(name)

    def list_tools(self) -> List[AgentTool]:
        """列出所有已注册工具"""
        return list(self._tools.values())

    async def execute(self, tool_name: str, args: Dict[str, Any]) -> str:
        """执行工具（沙箱：超时/异常隔离/限流）"""
        tool = self._tools.get(tool_name)
        if not tool:
            return f"Error: Tool '{tool_name}' not found"

        # 限流检查
        if tool.rate_limit > 0:
            count = self._call_counts.get(tool_name, 0)
            if count >= tool.rate_limit:
                return f"Error: Tool '{tool_name}' rate limit exceeded ({tool.rate_limit}/day)"
            self._call_counts[tool_name] = count + 1

        try:
            log.info(f"Execute tool: {tool_name}({args})")
            result = await asyncio.wait_for(
                tool.execute(**args),
                timeout=tool.timeout,
            )
            return str(result)
        except asyncio.TimeoutError:
            log.error(f"Tool timeout: {tool_name} ({tool.timeout}s)")
            return f"Error: Tool '{tool_name}' timed out after {tool.timeout}s"
        except Exception as e:
            log.error(f"Tool execution failed: {tool_name} — {e}")
            return f"Error: Tool execution failed — {e}"

    def get_json_schemas(self) -> List[Dict[str, Any]]:
        """获取所有工具的 OpenAI Function Calling JSON Schema"""
        schemas = []
        for tool in self._tools.values():
            schema = {
                "type": "function",
                "function": {
                    "name": tool.name,
                    "description": tool.description,
                    "parameters": self._extract_parameters(tool),
                },
            }
            schemas.append(schema)
        return schemas

    def _extract_parameters(self, tool: AgentTool) -> Dict[str, Any]:
        """从工具 execute 方法的签名中提取参数 JSON Schema"""
        sig = inspect.signature(tool.execute)
        properties = {}
        required = []

        for name, param in sig.parameters.items():
            if name == "self":
                continue
            annotation = param.annotation
            param_type = "string"
            if annotation is int:
                param_type = "integer"
            elif annotation is float:
                param_type = "number"
            elif annotation is bool:
                param_type = "boolean"

            properties[name] = {"type": param_type, "description": f"{name} 参数"}
            if param.default is inspect.Parameter.empty:
                required.append(name)

        return {
            "type": "object",
            "properties": properties,
            "required": required,
        }
