"""
gRPC 客户端 — 连接 Java Console 的 gRPC Server
"""
import logging

import grpc

from agent_runtime.grpc_client import agent_hub_pb2, agent_hub_pb2_grpc

log = logging.getLogger(__name__)

# gRPC Server 地址（开发环境）
GRPC_SERVER_ADDRESS = "localhost:9090"

_grpc_client: "GrpcClient | None" = None


def get_grpc_client() -> "GrpcClient":
    """获取全局 gRPC 客户端单例"""
    global _grpc_client
    if _grpc_client is None:
        _grpc_client = GrpcClient(GRPC_SERVER_ADDRESS)
    return _grpc_client


class GrpcClient:
    """Java Console gRPC 客户端"""

    def __init__(self, address: str):
        self.address = address
        self.channel: grpc.Channel | None = None

    def _get_channel(self) -> grpc.Channel:
        """获取或创建 gRPC Channel"""
        if self.channel is None:
            self.channel = grpc.insecure_channel(self.address)
        return self.channel

    @property
    def health_stub(self):
        """健康检查 Stub"""
        return agent_hub_pb2_grpc.HealthCheckStub(self._get_channel())

    @property
    def agent_stub(self):
        """Agent 执行 Stub"""
        return agent_hub_pb2_grpc.AgentExecutionStub(self._get_channel())

    @property
    def tool_stub(self):
        """工具注册 Stub"""
        return agent_hub_pb2_grpc.ToolRegistryStub(self._get_channel())

    @property
    def approval_stub(self):
        """审批回调 Stub"""
        return agent_hub_pb2_grpc.ApprovalCallbackStub(self._get_channel())

    def health_check(self) -> bool:
        """Ping Java Console gRPC Server"""
        try:
            request = agent_hub_pb2.PingRequest(
                sender="Python-Engine",
                timestamp=int(__import__("time").time() * 1000),
            )
            response = self.health_stub.Ping(request, timeout=5)
            log.info(f"Health check: {response.status} from {response.responder}")
            return response.status == "UP"
        except grpc.RpcError as e:
            log.error(f"gRPC 健康检查失败: {e.code()} - {e.details()}")
            return False

    def close(self):
        """关闭 Channel"""
        if self.channel:
            self.channel.close()
            self.channel = None
