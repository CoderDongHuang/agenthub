"""数学计算工具"""
from agent_runtime.tools.base import AgentTool


class CalculatorTool(AgentTool):
    name = "calculator"
    description = "Execute mathematical calculations. Input a math expression like 2+3*4."
    risk_level = "low"
    category = "通用"

    async def execute(self, expression: str) -> str:
        """
        Args:
            expression: 数学表达式，如 "2 + 3 * 4" 或 "sqrt(16)"
        """
        # 安全的白名单：只允许数字、运算符、括号、小数点、空格
        allowed = set("0123456789+-*/.() ^%")
        import math as _math
        safe_vars = {"math": _math, "sqrt": _math.sqrt, "pow": pow, "abs": abs, "round": round}

        cleaned = "".join(c for c in expression if c in allowed)
        if not cleaned:
            return "错误: 无效的数学表达式"

        try:
            result = eval(cleaned, {"__builtins__": {}}, safe_vars)
            return str(result)
        except Exception as e:
            return f"计算错误: {e}"
