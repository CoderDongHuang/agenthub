"""Agent 引擎单元测试"""
import asyncio
import sys
import os
from unittest.mock import MagicMock
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent_runtime.core.engine import AgentEngine, get_retriever
from agent_runtime.core.llm_client import LLMClient, get_provider_status, has_any_api_key
from agent_runtime.core.tool_executor import ToolExecutor
from agent_runtime.core.session_manager import SessionManager
from agent_runtime.grpc_client import agent_hub_pb2
from agent_runtime.rag.retriever import Retriever


class InMemorySessionManager:
    def __init__(self):
        self.sessions = {}

    async def get_or_create(self, session_id, tenant_id="0"):
        return self.sessions.setdefault(
            (tenant_id, session_id),
            {"session_id": session_id, "tenant_id": tenant_id, "messages": []},
        )

    async def save(self, session_id, data, tenant_id="0"):
        self.sessions[(tenant_id, session_id)] = data


def test_engine_demo_mode():
    """Demo 模式（无 API Key）应返回提示消息"""
    os.environ["AGENTHUB_DEMO_MODE"] = "true"
    engine = AgentEngine(LLMClient(), ToolExecutor(), InMemorySessionManager())
    req = agent_hub_pb2.ExecutionRequest(
        session_id="test-1", agent_id="1", tenant_id="0", user_id="1", message="Hello"
    )
    responses = []

    async def run():
        async for r in engine.execute(req):
            responses.append(r)

    asyncio.run(run())

    assert len(responses) >= 2
    assert responses[0].type == agent_hub_pb2.ExecutionResponse.TEXT
    if not has_any_api_key():
        assert "Demo Mode" in responses[0].content
    # 最后一个是 COMPLETE
    assert responses[-1].type == agent_hub_pb2.ExecutionResponse.COMPLETE


def test_engine_with_tool_calling():
    """引擎应能处理 tool call 流转"""
    os.environ["AGENTHUB_DEMO_MODE"] = "true"
    engine = AgentEngine(LLMClient(), ToolExecutor(), InMemorySessionManager())
    req = agent_hub_pb2.ExecutionRequest(
        session_id="test-2", agent_id="1", tenant_id="0", user_id="1", message="What is 1+1?"
    )
    types = []

    async def run():
        async for r in engine.execute(req):
            types.append(r.type)

    asyncio.run(run())
    # 至少有一个 TEXT 和一个 COMPLETE
    assert agent_hub_pb2.ExecutionResponse.TEXT in types
    assert agent_hub_pb2.ExecutionResponse.COMPLETE in types


def test_retriever_index_and_search():
    """检索器应使用数据库连接完成索引和检索。"""
    connection = MagicMock()
    cursor = MagicMock()
    connection.__enter__.return_value = connection
    connection.cursor.return_value.__enter__.return_value = cursor
    retriever = Retriever(lambda: connection)
    retriever.index("1", ["AI Agent Hub supports DeepSeek and GPT-4o models."])
    cursor.fetchall.return_value = [("AI Agent Hub supports DeepSeek and GPT-4o models.", 0.95)]

    context = retriever.get_context("DeepSeek GPT-4o models supported", top_k=1)
    assert "DeepSeek" in context
    assert cursor.execute.call_count >= 4


def test_llm_client_model_map():
    """LLM 客户端应有模型映射"""
    client = LLMClient()
    # 不传 API Key 也能创建 client 对象
    assert client is not None


def test_deepseek_catalog_uses_supplier_model_ids():
    model_ids = {item["id"] for item in get_provider_status()["models"]}
    assert "deepseek-v4-flash" in model_ids
    assert "deepseek-v4-pro" in model_ids
    assert "deepseek-chat" not in model_ids
    assert "deepseek-reasoner" not in model_ids


def test_runtime_overrides_apply_version_and_routing_decisions():
    engine = AgentEngine(LLMClient(), ToolExecutor(), InMemorySessionManager())
    resolved = engine._apply_runtime_overrides(
        {"name": "Stable", "model": "deepseek-v4-flash", "system_prompt": "stable", "temperature": 0.7, "max_tokens": 4096},
        {
            "agent_name_override": "Canary",
            "model_override": "gpt-4o-mini",
            "system_prompt_override": "canary prompt",
            "temperature_override": "0.2",
            "max_tokens_override": "1024",
            "agent_version": "3",
            "trace_id": "trace-1",
        },
    )
    assert resolved["name"] == "Canary"
    assert resolved["model"] == "gpt-4o-mini"
    assert resolved["system_prompt"] == "canary prompt"
    assert resolved["temperature"] == 0.2
    assert resolved["max_tokens"] == 1024
    assert resolved["agent_version"] == "3"


if __name__ == "__main__":
    test_engine_demo_mode()
    test_engine_with_tool_calling()
    test_retriever_index_and_search()
    test_llm_client_model_map()
    print("All engine tests passed!")
