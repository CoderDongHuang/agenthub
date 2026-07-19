"""Demo: 问候工具 — 自动发现示例"""
from agent_runtime.tools.base import AgentTool, tool


@tool(
    name="greeting",
    description="Send a greeting message to the user by name",
    risk_level="low",
    rate_limit=200,
    timeout=5,
    category="Demo",
)
class GreetingTool(AgentTool):

    async def execute(self, name: str) -> str:
        """
        Args:
            name: User's name to greet
        """
        return f"Hello {name}! Welcome to AI Agent Hub. Your custom tool is working!"
