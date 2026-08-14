"""
Python gRPC Server — grpc.aio 异步实现
Java Console 通过 gRPC 调用此服务来执行 Agent
"""
import logging
import asyncio
import time

import grpc

from agent_runtime.core.engine import AgentEngine
from agent_runtime.core.llm_client import LLMClient
from agent_runtime.core.session_manager import SessionManager
from agent_runtime.core.tool_executor import ToolExecutor
from agent_runtime.core.performance import runtime_metrics
from agent_runtime.grpc_client import agent_hub_pb2, agent_hub_pb2_grpc

log = logging.getLogger(__name__)

GRPC_LISTEN_PORT = 9091


class AgentExecutionServicer(agent_hub_pb2_grpc.AgentExecutionServicer):
    """Agent 执行 gRPC 服务 — async generator 实现双向流"""

    def __init__(self, engine: AgentEngine):
        self.engine = engine

    async def StreamExecute(self, request_iterator, context):
        """
        双向流: Java 发送 ExecutionRequest，Python 流式返回 ExecutionResponse
        async def + yield = async generator → grpc.aio 原生支持
        request_iterator 在 grpc.aio 中是 async iterable
        """
        async for request in request_iterator:
            log.info(f"Agent execution: session={request.session_id}, msg={request.message[:80]}...")
            started = time.perf_counter()
            first_token_recorded = False
            failed = False
            cancelled = False
            try:
                async for response in self.engine.execute(request):
                    if context.cancelled():
                        self.engine.cancel(request.session_id)
                        cancelled = True
                        break
                    if not first_token_recorded and response.type == agent_hub_pb2.ExecutionResponse.TEXT:
                        runtime_metrics.observe_first_token((time.perf_counter() - started) * 1000)
                        first_token_recorded = True
                    failed = failed or response.type == agent_hub_pb2.ExecutionResponse.ERROR
                    yield response
            except asyncio.CancelledError:
                self.engine.cancel(request.session_id)
                cancelled = True
                raise
            finally:
                runtime_metrics.observe_request(
                    (time.perf_counter() - started) * 1000,
                    error=failed,
                    cancelled=cancelled,
                )

    async def StopSession(self, request: agent_hub_pb2.StopSessionRequest, context):
        log.info(f"Stop session: {request.session_id}")
        cancelled = self.engine.cancel(request.session_id)
        return agent_hub_pb2.StopSessionResponse(
            success=cancelled,
            message="Cancellation requested" if cancelled else "Session is not running",
        )


class ToolRegistryServicer(agent_hub_pb2_grpc.ToolRegistryServicer):
    """工具注册 gRPC 服务"""

    def __init__(self, tool_executor: ToolExecutor):
        self.tool_executor = tool_executor

    async def CheckToolHealth(self, request, context):
        tool = self.tool_executor.get_tool(request.tool_id)
        healthy = tool is not None
        return agent_hub_pb2.ToolHealthResponse(healthy=healthy, message="OK" if healthy else "Not found")


class GrpcServer:
    """Python gRPC 服务器 (grpc.aio 异步)"""

    def __init__(self, engine: AgentEngine, tool_executor: ToolExecutor):
        self.engine = engine
        self.tool_executor = tool_executor
        self.server: grpc.aio.Server | None = None

    async def start(self):
        """启动 gRPC Server"""
        self.server = grpc.aio.server()

        agent_hub_pb2_grpc.add_AgentExecutionServicer_to_server(
            AgentExecutionServicer(self.engine), self.server
        )
        agent_hub_pb2_grpc.add_ToolRegistryServicer_to_server(
            ToolRegistryServicer(self.tool_executor), self.server
        )

        self.server.add_insecure_port(f"0.0.0.0:{GRPC_LISTEN_PORT}")
        await self.server.start()
        log.info(f"Python gRPC Server (aio) started on port {GRPC_LISTEN_PORT}")

    async def stop(self):
        """停止 gRPC Server"""
        if self.server:
            await self.server.stop(grace=5)
            log.info("Python gRPC Server stopped")

    async def wait_for_termination(self):
        """等待终止"""
        if self.server:
            await self.server.wait_for_termination()


# 全局单例
_engine: AgentEngine | None = None
_tool_executor: ToolExecutor | None = None


def get_engine() -> AgentEngine:
    global _engine, _tool_executor
    if _engine is None:
        llm_client = LLMClient()
        _tool_executor = ToolExecutor()
        session_manager = SessionManager()
        _engine = AgentEngine(llm_client, _tool_executor, session_manager)
    return _engine


def get_tool_executor() -> ToolExecutor:
    global _tool_executor
    if _tool_executor is None:
        get_engine()
    return _tool_executor
