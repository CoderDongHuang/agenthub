"""
AI Agent Hub — Python Agent 运行时
FastAPI 服务 + gRPC Client 入口
"""
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from agent_runtime.grpc_client.client import GrpcClient, get_grpc_client

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理"""
    log.info("Python Agent 运行时启动中...")
    # 启动时连接 gRPC Server 做健康检查
    try:
        client = get_grpc_client()
        if client.health_check():
            log.info("✅ gRPC 连接 Java Console 成功")
        else:
            log.warning("⚠️  gRPC 健康检查未通过，Java Console 可能未启动")
    except Exception as e:
        log.warning(f"⚠️  gRPC 连接失败: {e}，Java Console 可能未启动")
    yield
    log.info("Python Agent 运行时关闭")


app = FastAPI(
    title="AI Agent Hub - Python Engine",
    description="企业级 AI Agent 中台的 Agent 运行时引擎",
    version="0.1.0",
    lifespan=lifespan,
)


@app.get("/health")
async def health():
    """健康检查"""
    gprc_ok = False
    try:
        client = get_grpc_client()
        gprc_ok = client.health_check()
    except Exception:
        pass

    return {
        "status": "UP",
        "service": "AI Agent Hub - Python Engine",
        "grpc_connected": gprc_ok,
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
