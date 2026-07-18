"""网络搜索工具（Phase 1 用模拟实现，后续接入真实搜索 API）"""
from agent_runtime.tools.base import AgentTool


class WebSearchTool(AgentTool):
    name = "web_search"
    description = "Search the internet for the latest information. Input search keywords."
    risk_level = "medium"
    category = "通用"
    timeout = 15

    async def execute(self, query: str) -> str:
        """
        Args:
            query: search keywords
        """
        return (
            f"Search results for '{query}' (simulated):\n"
            f"1. {query} - Wikipedia\n"
            f"2. {query} latest news\n"
            f"3. {query} related resources\n"
            f"\nNote: Simulated search. Real search API will be integrated in Phase 3."
        )
