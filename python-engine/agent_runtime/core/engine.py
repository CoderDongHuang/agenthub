"""
Agent 执行引擎 — Python 运行时核心
Phase 1 实现完整的 ReAct 执行循环
"""
import logging
from typing import AsyncGenerator

log = logging.getLogger(__name__)


class AgentEngine:
    """
    Agent 执行引擎

    Phase 1 实现:
    - ReAct 循环（推理 → 行动 → 观察 → 推理）
    - 工具调用与审批卡点
    - 流式输出
    """

    def __init__(self, llm_client, tool_executor, session_manager):
        self.llm_client = llm_client
        self.tool_executor = tool_executor
        self.session_manager = session_manager

    async def execute(self, request) -> AsyncGenerator:
        """
        执行 Agent 对话（异步生成器，流式返回结果）

        Args:
            request: ExecutionRequest (gRPC 消息)

        Yields:
            ExecutionResponse: 流式返回的执行结果
        """
        # Phase 1 实现核心逻辑
        # 详见架构说明书第 4.3 节
        raise NotImplementedError("Phase 1 实现")
