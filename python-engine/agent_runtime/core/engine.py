"""
Agent 执行引擎 — Python 运行时核心
ReAct 循环: 推理 → 行动 → 观察 → 推理，支持流式输出 + RAG 检索
"""
import json
import logging
import os
from typing import AsyncGenerator, Dict, Any, List

from langchain_core.messages import HumanMessage, AIMessage, ToolMessage, SystemMessage, BaseMessage

from agent_runtime.core.llm_client import LLMClient, has_any_api_key
from agent_runtime.core.tool_executor import ToolExecutor
from agent_runtime.core.session_manager import SessionManager
from agent_runtime.grpc_client import agent_hub_pb2
from agent_runtime.rag.retriever import Retriever

log = logging.getLogger(__name__)

MAX_ITERATIONS = 10  # 最大 ReAct 循环次数
JAVA_CONSOLE_URL = os.getenv("JAVA_CONSOLE_URL", "http://localhost:8080").rstrip("/")
INTERNAL_API_TOKEN = os.getenv("AGENTHUB_INTERNAL_TOKEN", "")


def internal_headers(tenant_id: str) -> dict[str, str]:
    if len(INTERNAL_API_TOKEN) < 32:
        raise RuntimeError("AGENTHUB_INTERNAL_TOKEN must contain at least 32 characters")
    return {"X-Internal-Token": INTERNAL_API_TOKEN, "X-Tenant-Id": tenant_id}
RESPONSE_QUALITY_PROMPT = """

除非上面的业务指令明确要求其他格式，请遵循以下回答质量要求：
1. 使用与用户相同的语言，中文表达必须自然、完整、连贯。
2. 先直接回答核心问题，再补充必要依据或步骤；不要重复用户问题。
3. 只有在内容确实需要分层时才使用标题或列表，避免零碎短句和机械套话。
4. 保留代码、数字、专有名词和引用的准确性；无法确认的信息要明确说明，不得编造。
""".strip()

# 全局检索器单例
_global_retriever: Retriever | None = None


def get_retriever() -> Retriever:
    global _global_retriever
    if _global_retriever is None:
        _global_retriever = Retriever()
    return _global_retriever


class AgentEngine:
    """Agent 执行引擎"""

    def __init__(self, llm_client: LLMClient, tool_executor: ToolExecutor, session_manager: SessionManager):
        self.llm_client = llm_client
        self.tool_executor = tool_executor
        self.session_manager = session_manager

    async def execute(self, request: agent_hub_pb2.ExecutionRequest) -> AsyncGenerator[agent_hub_pb2.ExecutionResponse, None]:
        """
        执行 Agent 对话，流式返回结果

        Args:
            request: 来自 gRPC 的执行请求 (session_id, agent_id, user_id, message, etc.)
        """
        session_id = request.session_id
        user_message = request.message

        log.info(f"Agent 执行开始: session={session_id}, msg={user_message[:50]}...")

        try:
            # 1. 获取会话上下文
            session = await self.session_manager.get_or_create(session_id)
            history = session.get("messages", [])

            # 2. 加载 Agent 配置（Phase 1: 使用硬编码配置，后续从 Java 获取）
            agent_config = await self._load_agent_config(request.agent_id, request.tenant_id)

            # 3. 获取 LLM 并绑定工具
            tool_schemas = self.tool_executor.get_json_schemas()
            tool_policy_lines = [
                f"- {tool.name}: 风险等级 {tool.risk_level}，"
                f"{'调用前必须经过人工审批' if tool.risk_level in ('medium', 'high') else '可以直接执行'}"
                for tool in self.tool_executor.list_tools()
            ]
            tool_policy_context = "\n".join(tool_policy_lines) if tool_policy_lines else "- 当前没有可用工具"

            # Demo 模式：没有 API Key 时用本地模拟，保证端到端流程可跑
            if not has_any_api_key():
                log.warning("No LLM API Key found, using Demo mode")
                demo_reply = (
                    f"[Demo Mode] Received: {user_message}\n\n"
                    f"Agent ID: {request.agent_id}\n"
                    f"Available tools: {len(tool_schemas)}\n"
                    f"Tip: Set OPENAI_API_KEY or ANTHROPIC_API_KEY env var to enable real LLM calls."
                )
                yield self._make_response(agent_hub_pb2.ExecutionResponse.TEXT, content=demo_reply)

                # 保存会话
                history.append({"role": "user", "content": user_message})
                history.append({"role": "assistant", "content": demo_reply})
                session["messages"] = history[-100:]
                await self.session_manager.save(session_id, session)

                yield self._make_response(agent_hub_pb2.ExecutionResponse.COMPLETE, content="")
                return

            llm = self.llm_client.get_model(
                model_name=agent_config["model"],
                temperature=agent_config.get("temperature", 0.7),
                max_tokens=agent_config.get("max_tokens", 4096),
            )
            llm_with_tools = llm.bind_tools(tool_schemas) if tool_schemas else llm

            # 4. 构建消息列表（含 RAG 检索上下文）
            system_prompt = (
                f"你是 AgentHub 工作台中配置的 Agent“{agent_config['name']}”，"
                f"当前底层模型为 {agent_config['model']}。不要把模型供应商身份与 Agent 身份混为一谈。\n\n"
                f"{agent_config['system_prompt'].strip()}\n\n"
                "AgentHub 的治理规则：低风险工具可直接执行；中风险和高风险工具必须在实际调用前获得人工审批。"
                "当前工具风险如下：\n"
                f"{tool_policy_context}\n\n"
                f"{RESPONSE_QUALITY_PROMPT}"
            )
            # 尝试从知识库检索相关上下文
            try:
                retriever = get_retriever()
                rag_context = retriever.get_context(user_message, top_k=3, tenant_id=request.tenant_id)
                if rag_context:
                    system_prompt += f"\n\n[Relevant Knowledge]\n{rag_context}\n\nUse the above knowledge to answer the user's question accurately."
                    log.info(f"RAG context injected ({len(rag_context)} chars)")
            except Exception as e:
                log.debug(f"RAG retrieval skipped: {e}")

            messages: List[BaseMessage] = [SystemMessage(content=system_prompt)]
            # 添加历史消息（最近 20 轮）
            for msg in history[-40:]:
                messages.append(self._dict_to_message(msg))
            messages.append(HumanMessage(content=user_message))

            # 5. ReAct 循环
            iteration = 0
            full_text = ""
            while iteration < MAX_ITERATIONS:
                iteration += 1

                # 5.1 用非流式调用获取完整 tool_calls（DeepSeek 流式下 args 为空）
                response = await llm_with_tools.ainvoke(messages)

                # 5.2 如果有工具调用，执行并继续循环
                if hasattr(response, "tool_calls") and response.tool_calls:
                    tool_calls = response.tool_calls
                    messages.append(response)  # AIMessage with tool_calls

                    for tc in tool_calls:
                        tool_name = tc.get("name", "")
                        tool_args = tc.get("args", {})

                        yield self._make_response(
                            agent_hub_pb2.ExecutionResponse.TOOL_START,
                            content=f"Calling tool: {tool_name}",
                            tool_name=tool_name,
                        )

                        # 解析参数
                        if isinstance(tool_args, str):
                            try:
                                tool_args = json.loads(tool_args)
                            except json.JSONDecodeError:
                                tool_args = {}
                        if not isinstance(tool_args, dict):
                            tool_args = {}

                        log.info(f"Tool call: {tool_name}({tool_args})")

                        # 审批卡点：检查工具风险等级
                        tool = self.tool_executor.get_tool(tool_name)
                        risk_level = tool.risk_level if tool else "low"

                        if risk_level in ("medium", "high"):
                            approval_type = "dual" if risk_level == "high" else "single"
                            # 创建审批请求
                            try:
                                approval_id = await self._create_approval(
                                    request.session_id, request.agent_id,
                                    tool_name, request.user_id, str(tool_args), risk_level, request.tenant_id
                                )
                                yield self._make_response(
                                    agent_hub_pb2.ExecutionResponse.APPROVAL_WAIT,
                                    content=f"Need approval [{risk_level}] for: {tool_name}. Approval ID: {approval_id}. Please approve to continue.",
                                    tool_name=tool_name,
                                )
                                # 轮询等待审批结果
                                approved = await self._wait_approval(
                                    approval_id, request.tenant_id, timeout=120
                                )
                                if not approved:
                                    yield self._make_response(
                                        agent_hub_pb2.ExecutionResponse.ERROR,
                                        content=f"Approval rejected or timeout for: {tool_name}",
                                    )
                                    messages.append(ToolMessage(
                                        content="Operation rejected: approval not granted",
                                        tool_call_id=tc.get("id", "")
                                    ))
                                    continue
                            except Exception as e:
                                log.warning(f"Approval request failed: {e}, skipping tool execution")
                                yield self._make_response(
                                    agent_hub_pb2.ExecutionResponse.ERROR,
                                    content=f"Approval system unavailable: {e}",
                                )
                                continue

                        # 执行工具
                        result = await self.tool_executor.execute(tool_name, tool_args)
                        yield self._make_response(
                            agent_hub_pb2.ExecutionResponse.TOOL_END,
                            content=result,
                            tool_name=tool_name,
                        )

                        messages.append(ToolMessage(content=result, tool_call_id=tc.get("id", "")))

                else:
                    # 5.3 最终回复 — 流式输出
                    full_text = response.content if hasattr(response, "content") else str(response)

                    # 如果这个响应没有 tool_calls，流式发送文本
                    if full_text:
                        for start in range(0, len(full_text), 24):
                            chunk = full_text[start:start + 24]
                            yield self._make_response(
                                agent_hub_pb2.ExecutionResponse.TEXT,
                                content=chunk,
                            )

                    messages.append(response)
                    break

            # 6. 保存会话
            history.append({"role": "user", "content": user_message})
            if full_text:
                history.append({"role": "assistant", "content": full_text})
            session["messages"] = history[-100:]  # 保留最近 100 条
            await self.session_manager.save(session_id, session)

            # 7. 完成
            yield self._make_response(agent_hub_pb2.ExecutionResponse.COMPLETE, content="")

        except Exception as e:
            log.error(f"Agent 执行异常: {e}", exc_info=True)
            yield self._make_response(agent_hub_pb2.ExecutionResponse.ERROR, content=str(e))

    async def _load_agent_config(self, agent_id: str, tenant_id: str) -> Dict[str, Any]:
        """
        从 Java Console 加载 Agent 配置（通过 HTTP REST API）
        """
        default_config = {
            "name": f"Agent {agent_id}",
            "model": "deepseek-v4-flash",
            "system_prompt": "你是一个有用的 AI 助手。你可以使用提供的工具来帮助用户。",
            "temperature": 0.7,
            "max_tokens": 4096,
        }

        try:
            import httpx
            async with httpx.AsyncClient(timeout=5) as client:
                resp = await client.get(
                    f"{JAVA_CONSOLE_URL}/api/internal/agents/{agent_id}",
                    headers=internal_headers(tenant_id),
                )
                if resp.status_code == 200:
                    data = resp.json().get("data", {})
                    return {
                        "name": data.get("name", default_config["name"]),
                        "model": data.get("model", default_config["model"]),
                        "system_prompt": data.get("systemPrompt", default_config["system_prompt"]),
                        "temperature": float(data.get("temperature", default_config["temperature"])),
                        "max_tokens": data.get("maxTokens", default_config["max_tokens"]),
                    }
                log.warning("Java Agent config returned HTTP %s", resp.status_code)
        except Exception as e:
            log.warning(f"无法从 Java 加载 Agent 配置: {e}，使用默认配置")

        return default_config

    async def _create_approval(self, session_id: str, agent_id: str, tool_name: str,
                                user_id: str, context: str, risk_level: str, tenant_id: str) -> str:
        """通过 Java REST API 创建审批请求，返回审批 ID"""
        import httpx
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.post(
                f"{JAVA_CONSOLE_URL}/api/approvals/create",
                json={
                    "sessionId": session_id,
                    "agentId": agent_id,
                    "toolName": tool_name,
                    "requesterId": int(user_id) if user_id.isdigit() else 1,
                    "reason": f"Agent {agent_id} requests to use tool [{tool_name}] (risk: {risk_level})",
                    "context": context,
                },
                headers=internal_headers(tenant_id),
            )
            if resp.status_code == 200:
                data = resp.json().get("data", {})
                return str(data.get("id", ""))
            raise Exception(f"Failed to create approval: {resp.status_code}")

    async def _wait_approval(self, approval_id: str, tenant_id: str, timeout: int = 120) -> bool:
        """轮询 Java API 等待审批结果，超时返回 False"""
        import asyncio
        import httpx
        deadline = asyncio.get_event_loop().time() + timeout
        async with httpx.AsyncClient(timeout=5) as client:
            while asyncio.get_event_loop().time() < deadline:
                await asyncio.sleep(3)  # 每 3 秒检查一次
                try:
                    resp = await client.get(
                        f"{JAVA_CONSOLE_URL}/api/approvals/{approval_id}",
                        headers=internal_headers(tenant_id),
                    )
                    if resp.status_code == 200:
                        status = resp.json().get("data", {}).get("status", "pending")
                        if status == "approved":
                            return True
                        elif status == "rejected":
                            return False
                except Exception:
                    pass  # 网络错误继续重试
        return False

    def _make_response(self, resp_type, content="", tool_name="", tool_id="", token_count=0.0) -> agent_hub_pb2.ExecutionResponse:
        """构造 gRPC 响应消息"""
        return agent_hub_pb2.ExecutionResponse(
            type=resp_type,
            content=content,
            tool_name=tool_name,
            tool_id=tool_id,
            token_count=token_count,
        )

    def _dict_to_message(self, msg: Dict) -> BaseMessage:
        """将字典转换为 LangChain Message"""
        role = msg.get("role", "user")
        content = msg.get("content", "")
        if role == "user":
            return HumanMessage(content=content)
        elif role == "assistant":
            return AIMessage(content=content)
        elif role == "tool":
            return ToolMessage(content=content, tool_call_id=msg.get("tool_call_id", ""))
        elif role == "system":
            return SystemMessage(content=content)
        return HumanMessage(content=content)
