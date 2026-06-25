"""
工具基类和 @tool 装饰器
"""
import functools
from typing import Any, Callable


class AgentTool:
    """AI Agent 工具基类"""

    name: str = ""
    description: str = ""
    risk_level: str = "low"  # low / medium / high
    rate_limit: int = 0      # 0 = 不限制
    timeout: int = 30        # 秒
    category: str = "通用"

    async def execute(self, **kwargs) -> Any:
        """执行工具（子类必须实现）"""
        raise NotImplementedError


def tool(
    name: str,
    description: str,
    risk_level: str = "low",
    rate_limit: int = 0,
    timeout: int = 30,
    category: str = "通用",
):
    """
    工具注册装饰器

    用法:
        @tool(name="客户查询", description="根据手机号查询客户", risk_level="medium")
        class CRMQueryTool(AgentTool):
            async def execute(self, phone: str) -> dict:
                ...
    """
    def decorator(cls):
        cls.name = name
        cls.description = description
        cls.risk_level = risk_level
        cls.rate_limit = rate_limit
        cls.timeout = timeout
        cls.category = category
        return cls
    return decorator
