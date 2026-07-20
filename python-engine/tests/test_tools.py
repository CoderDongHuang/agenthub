"""工具执行器 + 内置工具单元测试"""
import asyncio
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent_runtime.tools.builtin.calculator import CalculatorTool
from agent_runtime.tools.builtin.datetime_tool import DateTimeTool
from agent_runtime.core.tool_executor import ToolExecutor


def test_calculator_valid():
    tool = CalculatorTool()
    result = asyncio.run(tool.execute(expression="2+3*4"))
    assert "14" in result, f"Expected 14, got {result}"


def test_calculator_invalid():
    """Invalid expressions should not execute dangerous code"""
    tool = CalculatorTool()
    result = asyncio.run(tool.execute(expression="__import__('os')"))
    # Dangerous input is stripped — only safe chars remain
    assert "import" not in result.lower()
    assert "os" not in result.lower()


def test_datetime_now():
    tool = DateTimeTool()
    result = asyncio.run(tool.execute(operation="now"))
    assert len(result) > 0


def test_datetime_add_days():
    tool = DateTimeTool()
    result = asyncio.run(tool.execute(operation="add_days", value="7"))
    assert "7 days later" in result


def test_tool_executor_register_and_execute():
    executor = ToolExecutor()
    assert executor.get_tool("calculator") is not None
    assert executor.get_tool("datetime") is not None
    assert executor.get_tool("web_search") is not None

    result = asyncio.run(executor.execute("calculator", {"expression": "100/4"}))
    assert "25" in result


def test_tool_executor_unknown_tool():
    executor = ToolExecutor()
    result = asyncio.run(executor.execute("nonexistent", {}))
    assert "not found" in result.lower()


def test_json_schemas():
    executor = ToolExecutor()
    schemas = executor.get_json_schemas()
    assert len(schemas) >= 3
    calc = [s for s in schemas if s["function"]["name"] == "calculator"][0]
    assert "expression" in str(calc["function"]["parameters"])


if __name__ == "__main__":
    test_calculator_valid()
    test_calculator_invalid()
    test_datetime_now()
    test_datetime_add_days()
    test_tool_executor_register_and_execute()
    test_tool_executor_unknown_tool()
    test_json_schemas()
    print("All Python tests passed!")
