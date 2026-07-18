"""日期时间工具"""
from datetime import datetime, timezone, timedelta
from agent_runtime.tools.base import AgentTool


class DateTimeTool(AgentTool):
    name = "datetime"
    description = "Get current datetime, calculate date differences. Operations: now, weekday, add_days."
    risk_level = "low"
    category = "通用"

    async def execute(self, operation: str = "now", value: str = "") -> str:
        """
        Args:
            operation: now / weekday / add_days
            value: days to add for add_days (positive=future, negative=past)
        """
        now = datetime.now(timezone(timedelta(hours=8)))

        if operation == "now":
            days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
            return f"Current: {now.strftime('%Y-%m-%d %H:%M:%S')} ({days[now.weekday()]})"

        elif operation == "weekday":
            days = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
            return f"Today is {days[now.weekday()]}"

        elif operation == "add_days":
            try:
                days = int(value) if value else 0
                future = now + timedelta(days=days)
                return f"{days} days later: {future.strftime('%Y-%m-%d')}"
            except ValueError:
                return f"Error: '{value}' is not a valid number of days"

        return f"Unknown operation: {operation}, supported: now, weekday, add_days"
