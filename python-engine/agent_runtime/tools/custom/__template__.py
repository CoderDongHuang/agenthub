"""
自定义工具模板 — 复制此文件并修改，放到 custom/ 目录即可自动注册。

用法:
1. 复制此文件为 your_tool.py
2. 修改类名、@tool 参数、execute 方法
3. 重启 Python Engine → 自动发现并同步到 Java
"""
from agent_runtime.tools.base import AgentTool, tool


@tool(
    name="my_tool",              # 工具名（英文，匹配 ^[a-zA-Z0-9_-]+$）
    description="Tool description shown to LLM",
    risk_level="low",            # low/medium/high → 决定审批策略
    rate_limit=0,                # 0=不限, N=每天最多调用N次
    timeout=30,                  # 超时秒数
    category="自定义",
)
class MyCustomTool(AgentTool):
    """你的自定义工具"""

    async def execute(self, param1: str) -> str:
        """
        Args:
            param1: 参数说明（会自动生成 JSON Schema）
        """
        # 在这里写你的业务逻辑
        return f"Result: {param1}"
