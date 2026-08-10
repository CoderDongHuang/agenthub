"""
AI Agent Hub — Python Agent 运行时
FastAPI 服务 + gRPC Server 入口
"""
import logging
import os
import time
from collections import Counter
from pathlib import Path

# 自动加载 .env 文件（项目根目录）
_dotenv_path = Path(__file__).parent.parent / ".env"
if _dotenv_path.exists():
    try:
        from dotenv import load_dotenv
        load_dotenv(_dotenv_path)
    except ImportError:
        pass

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from agent_runtime.core.engine import get_retriever
from agent_runtime.core.llm_client import get_provider_status
from agent_runtime.core.tool_executor import ToolExecutor
from agent_runtime.grpc_client.client import get_grpc_client
from agent_runtime.grpc_client.server import GrpcServer, get_engine, get_tool_executor
from agent_runtime.rag.chunker import DocumentChunker

logging.basicConfig(level=logging.INFO)
log = logging.getLogger(__name__)

STARTED_AT = time.monotonic()
_rag_bootstrapped = False
JAVA_CONSOLE_URL = os.getenv("JAVA_CONSOLE_URL", "http://localhost:8080").rstrip("/")

grpc_server = GrpcServer(get_engine(), get_tool_executor())


async def bootstrap_rag_index():
    """从 PostgreSQL 中保存的分块恢复内存向量索引。"""
    global _rag_bootstrapped
    if _rag_bootstrapped:
        return True
    try:
        import httpx
        async with httpx.AsyncClient(timeout=15) as client:
            response = await client.get(f"{JAVA_CONSOLE_URL}/api/knowledge/docs/chunks")
            response.raise_for_status()
            rows = response.json().get("data", [])
        grouped = {}
        for row in rows:
            grouped.setdefault(str(row["doc_id"]), []).append(row["content"])
        retriever = get_retriever()
        for doc_id, chunks in grouped.items():
            retriever.index(doc_id, chunks)
        _rag_bootstrapped = True
        log.info("Restored %s knowledge documents from PostgreSQL", len(grouped))
        return True
    except Exception as exception:
        log.warning("Knowledge index bootstrap deferred: %s", exception)
        return False


async def startup_event():
    log.info("Python Agent 运行时启动中...")
    # 启动 gRPC Server（grpc.aio 异步）
    await grpc_server.start()
    # 连接 Java Console gRPC 做健康检查
    try:
        client = get_grpc_client()
        if client.health_check():
            log.info("gRPC 连接 Java Console 成功")
        else:
            log.warning("gRPC 健康检查未通过，Java Console 可能未启动")
    except Exception as e:
        log.warning(f"gRPC 连接失败: {e}，Java Console 可能未启动")
    # 同步工具到 Java Console
    try:
        tool_executor = get_tool_executor()
        await tool_executor.sync_to_java()
        log.info(f"Synced {len(tool_executor.list_tools())} tools to Java Console")
    except Exception as e:
        log.warning(f"Tool sync failed: {e}")

    await bootstrap_rag_index()

    log.info("Python Agent 运行时已就绪")


async def shutdown_event():
    log.info("Python Agent 运行时关闭中...")
    await grpc_server.stop()


app = FastAPI(
    title="AI Agent Hub - Python Engine",
    description="企业级 AI Agent 中台的 Agent 运行时引擎",
    version="0.1.0",
)

configured_origins = [
    origin.strip()
    for origin in os.getenv("AGENTHUB_CORS_ORIGINS", "").split(",")
    if origin.strip()
]
app.add_middleware(
    CORSMiddleware,
    allow_origins=configured_origins,
    allow_origin_regex=r"^https?://(localhost|127\.0\.0\.1)(:\d+)?$",
    allow_credentials=False,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)

app.add_event_handler("startup", startup_event)
app.add_event_handler("shutdown", shutdown_event)


@app.get("/health")
async def health():
    """健康检查"""
    grpc_ok = False
    try:
        client = get_grpc_client()
        grpc_ok = client.health_check()
    except Exception:
        pass

    return {
        "status": "UP",
        "service": "AI Agent Hub - Python Engine",
        "grpc_to_java": grpc_ok,
    }


@app.post("/rag/index")
async def rag_index(doc_id: int = None):
    """从 Java DB 获取文档内容，分块并索引到向量存储"""
    if doc_id is None:
        return {"status": "error", "message": "doc_id is required"}

    try:
        import httpx
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.get(f"{JAVA_CONSOLE_URL}/api/knowledge/docs/{doc_id}")
            if resp.status_code != 200:
                return {"status": "error", "message": f"Failed to fetch doc: {resp.status_code}"}
            doc = resp.json().get("data", {})
            content = doc.get("content", "")
            filename = doc.get("filename", "unknown")

        if not content:
            return {"status": "error", "message": "Document has no content"}

        # 分块
        chunker = DocumentChunker(chunk_size=500, chunk_overlap=100)
        chunks = chunker.chunk(content)

        # 索引
        retriever = get_retriever()
        retriever.index(str(doc_id), chunks)

        async with httpx.AsyncClient(timeout=30) as client:
            persist_response = await client.post(
                f"{JAVA_CONSOLE_URL}/api/knowledge/docs/{doc_id}/chunks",
                json={"chunks": chunks},
            )
            persist_response.raise_for_status()

        return {
            "status": "ok",
            "doc_id": doc_id,
            "filename": filename,
            "chunks": len(chunks),
            "total_indexed": retriever.stats()["total_chunks"],
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}


@app.get("/rag/stats")
async def rag_stats():
    """获取检索器统计"""
    await bootstrap_rag_index()
    retriever = get_retriever()
    return {"status": "ok", "stats": retriever.stats()}


@app.delete("/rag/docs/{doc_id}")
async def rag_delete(doc_id: int):
    """移除指定文档的运行时索引。"""
    get_retriever().remove(str(doc_id))
    return {"status": "ok", "doc_id": doc_id}


@app.get("/runtime/capabilities")
async def runtime_capabilities():
    """提供给 Java 管理面的运行时能力快照。"""
    await bootstrap_rag_index()
    tool_executor = get_tool_executor()
    tools = tool_executor.list_tools()
    risk_distribution = Counter(tool.risk_level for tool in tools)
    retriever_stats = get_retriever().stats()
    model_status = get_provider_status()

    return {
        "status": "UP",
        "service": "python-runtime",
        "version": app.version,
        "uptime_seconds": int(time.monotonic() - STARTED_AT),
        "grpc_port": 9091,
        "models": model_status,
        "tools": {
            "count": len(tools),
            "risk_distribution": dict(risk_distribution),
            "items": [
                {
                    "name": tool.name,
                    "risk_level": tool.risk_level,
                    "timeout_seconds": tool.timeout,
                    "rate_limit": tool.rate_limit,
                }
                for tool in tools
            ],
        },
        "rag": retriever_stats,
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
