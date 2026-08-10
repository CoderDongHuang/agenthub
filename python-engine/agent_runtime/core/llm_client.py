"""
LLM 客户端封装 — 12 家公司 24 个模型，每家推理最强 + 速度最快各一个
OpenAI 兼容接口统一调用，Anthropic/Gemini 走专用 Client，设 Key 即用
加新模型只需在 MODEL_CONFIG 加一行 + 前端 modelOptions 加一行
"""
import logging
import os
from typing import Dict

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_openai import ChatOpenAI

log = logging.getLogger(__name__)

# ═══════════════════════════════════════════════════════════════
# 模型配置: { 前端选的值: (provider, 环境变量, API地址) }
# 加新模型只需在这里加一行 + 前端 modelOptions 加一行
# base_url=None 时，GPT 读 OPENAI_BASE_URL 环境变量，Claude 读 ANTHROPIC_BASE_URL
# 配置中转: export OPENAI_BASE_URL=https://your-proxy.com/v1
# ⚠️ Anthropic 不走 ChatOpenAI，见 _create_model 分支
# ═══════════════════════════════════════════════════════════════
MODEL_CONFIG: Dict[str, tuple] = {
    # ── OpenAI ──
    "gpt-4o":              ("openai",     "OPENAI_API_KEY",         None),       # 🧠 推理
    "gpt-4o-mini":         ("openai",     "OPENAI_API_KEY",         None),       # ⚡ 速度

    # ── Anthropic Claude（需 ChatAnthropic，非 OpenAI 协议）──
    "claude-opus-4-8":     ("anthropic",  "ANTHROPIC_API_KEY",      None),       # 🧠 推理
    "claude-sonnet-5":     ("anthropic",  "ANTHROPIC_API_KEY",      None),       # ⚡ 速度

    # ── DeepSeek（2026.04 发布 V4 系列）──
    "deepseek-v4-pro":     ("deepseek",   "DEEPSEEK_API_KEY",      "https://api.deepseek.com/v1"),  # 🧠 推理 1.6T/49B MoE
    "deepseek-v4-flash":   ("deepseek",   "DEEPSEEK_API_KEY",      "https://api.deepseek.com/v1"),  # ⚡ 速度 284B/13B MoE

    # ── 阿里通义千问 ──
    "qwen-max":            ("qwen",       "DASHSCOPE_API_KEY",     "https://dashscope.aliyuncs.com/compatible-mode/v1"),  # 🧠 推理
    "qwen-plus":           ("qwen",       "DASHSCOPE_API_KEY",     "https://dashscope.aliyuncs.com/compatible-mode/v1"),  # ⚡ 速度

    # ── Moonshot Kimi（官方 API china 域名）──
    "kimi-k2.6":           ("moonshot",   "MOONSHOT_API_KEY",      "https://api.moonshot.cn/v1"),  # 🧠 推理 K2.6
    "kimi-k2.7-code-hs":   ("moonshot",   "MOONSHOT_API_KEY",      "https://api.moonshot.cn/v1"),  # ⚡ 速度 编码专用高速

    # ── 智谱 GLM（2026 最新 4.7 系列）──
    "glm-4.7":             ("zhipu",      "ZHIPU_API_KEY",         "https://open.bigmodel.cn/api/paas/v4"),  # 🧠 推理
    "glm-4.7-flash":       ("zhipu",      "ZHIPU_API_KEY",         "https://open.bigmodel.cn/api/paas/v4"),  # ⚡ 速度 免费

    # ── 字节豆包（⚠️ 需火山引擎签名认证，非标准 API Key）──
    # "doubao-seed-2.0-pro": ("doubao", "DOUBAO_API_KEY", "https://ark.cn-beijing.volces.com/api/v3"),
    # "doubao-seed-2.0-lite":("doubao", "DOUBAO_API_KEY", "https://ark.cn-beijing.volces.com/api/v3"),

    # ── 百川（OpenAI 兼容）──
    "baichuan4":           ("baichuan",   "BAICHUAN_API_KEY",      "https://api.baichuan-ai.com/v1"),  # 🧠 推理
    "baichuan4-turbo":     ("baichuan",   "BAICHUAN_API_KEY",      "https://api.baichuan-ai.com/v1"),  # ⚡ 速度

    # ── xAI Grok（OpenAI 兼容，马斯克）──
    "grok-4.1":            ("xai",        "XAI_API_KEY",           "https://api.x.ai/v1"),  # 🧠 推理 2M上下文
    "grok-4.1-fast":       ("xai",        "XAI_API_KEY",           "https://api.x.ai/v1"),  # ⚡ 极速 $0.20/$0.50

    # ── Google Gemini（⚠️ 非 OpenAI 协议，需代理）──
    # "gemini-2.5-pro":   ("google", "GEMINI_API_KEY", "https://generativelanguage.googleapis.com/v1beta"),
    # "gemini-2.5-flash": ("google", "GEMINI_API_KEY", "https://generativelanguage.googleapis.com/v1beta"),

    # ── Mistral AI（OpenAI 兼容，法国）──
    "mistral-large":       ("mistral",    "MISTRAL_API_KEY",       "https://api.mistral.ai/v1"),  # 🧠 推理
    "mistral-small":       ("mistral",    "MISTRAL_API_KEY",       "https://api.mistral.ai/v1"),  # ⚡ 速度

    # ── 科大讯飞 星火（OpenAI 兼容）──
    "spark-4.0-ultra":     ("spark",      "SPARK_API_KEY",         "https://spark-api-open.xf-yun.com/v1"),  # 🧠 推理
    "spark-lite":          ("spark",      "SPARK_API_KEY",         "https://spark-api-open.xf-yun.com/v1"),  # ⚡ 速度

    # ── 腾讯混元（OpenAI 兼容，腾讯云）──
    "hunyuan-turbo":       ("hunyuan",    "HUNYUAN_API_KEY",       "https://api.hunyuan.cloud.tencent.com/v1"),  # 🧠 推理
    "hunyuan-lite":        ("hunyuan",    "HUNYUAN_API_KEY",       "https://api.hunyuan.cloud.tencent.com/v1"),  # ⚡ 速度

    # ── MiniMax（OpenAI 兼容）──
    "minimax-text-01":     ("minimax",    "MINIMAX_API_KEY",       "https://api.minimax.chat/v1"),  # 🧠 推理 4M上下文
    "minimax-abab6.5s":    ("minimax",    "MINIMAX_API_KEY",       "https://api.minimax.chat/v1"),  # ⚡ 速度
}


def get_api_key(provider: str) -> str | None:
    """获取指定 provider 的 API Key"""
    key_map = {
        "openai":    "OPENAI_API_KEY",
        "anthropic": "ANTHROPIC_API_KEY",
        "deepseek":  "DEEPSEEK_API_KEY",
        "qwen":      "DASHSCOPE_API_KEY",
        "moonshot":  "MOONSHOT_API_KEY",
        "zhipu":     "ZHIPU_API_KEY",
        "baichuan":  "BAICHUAN_API_KEY",
        "xai":       "XAI_API_KEY",
        "mistral":   "MISTRAL_API_KEY",
        "spark":     "SPARK_API_KEY",
        "hunyuan":   "HUNYUAN_API_KEY",
        "minimax":   "MINIMAX_API_KEY",
    }
    env_var = key_map.get(provider, "OPENAI_API_KEY")
    return os.getenv(env_var)


def has_any_api_key() -> bool:
    """是否配置了任意 LLM API Key"""
    return any(
        os.getenv(v) for v in [
            "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "DEEPSEEK_API_KEY",
            "DASHSCOPE_API_KEY", "MOONSHOT_API_KEY", "ZHIPU_API_KEY",
            "BAICHUAN_API_KEY", "XAI_API_KEY",
            "MISTRAL_API_KEY", "SPARK_API_KEY",
            "HUNYUAN_API_KEY", "MINIMAX_API_KEY",
        ]
    )


def get_provider_status() -> dict:
    """返回模型供应商配置状态，不暴露任何凭证内容。"""
    providers: dict[str, dict] = {}
    for model_name, (provider, key_env, _) in MODEL_CONFIG.items():
        item = providers.setdefault(
            provider,
            {
                "provider": provider,
                "configured": bool(os.getenv(key_env)),
                "models": [],
            },
        )
        item["models"].append(model_name)

    provider_list = sorted(providers.values(), key=lambda item: item["provider"])
    return {
        "providers": provider_list,
        "provider_count": len(provider_list),
        "configured_provider_count": sum(1 for item in provider_list if item["configured"]),
        "model_count": len(MODEL_CONFIG),
    }


class LLMClient:
    """多模型 LLM 客户端 — 12 家公司 24 个模型统一入口"""

    def __init__(self):
        self._models: Dict[str, BaseChatModel] = {}

    def get_model(self, model_name: str, temperature: float = 0.7, max_tokens: int = 4096) -> BaseChatModel:
        """获取或创建 ChatModel 实例（带缓存）"""
        cache_key = f"{model_name}_{temperature}_{max_tokens}"
        if cache_key not in self._models:
            self._models[cache_key] = self._create_model(model_name, temperature, max_tokens)
        return self._models[cache_key]

    def _create_model(self, model_name: str, temperature: float, max_tokens: int) -> BaseChatModel:
        """根据模型名创建对应实例"""
        config = MODEL_CONFIG.get(model_name)
        if not config:
            log.warning(f"Unknown model: {model_name}, fallback to OpenAI default")
            provider, key_env, base_url = "openai", "OPENAI_API_KEY", None
        else:
            provider, key_env, base_url = config

        api_key = get_api_key(provider)

        # Anthropic: 配了中转就走 ChatOpenAI（api2d 等封装成 OpenAI 协议），没配走原生 ChatAnthropic
        if provider == "anthropic":
            anthropic_base = os.getenv("ANTHROPIC_BASE_URL")
            if anthropic_base:
                # 中转模式：走 ChatOpenAI，base_url 指向中转服务
                log.info(f"Load model: {model_name} via proxy ({anthropic_base})")
                kwargs = dict(
                    model=model_name,
                    temperature=temperature,
                    max_tokens=max_tokens,
                    streaming=True,
                )
                if api_key:
                    kwargs["openai_api_key"] = api_key
                kwargs["openai_api_base"] = anthropic_base
                return ChatOpenAI(**kwargs)
            else:
                # 原生模式：走 ChatAnthropic 直连
                try:
                    from langchain_anthropic import ChatAnthropic
                    log.info(f"Load model: {model_name} via ChatAnthropic (direct)")
                    return ChatAnthropic(
                        model=model_name,
                        temperature=temperature,
                        max_tokens=max_tokens,
                        streaming=True,
                    )
                except ImportError:
                    log.warning("langchain_anthropic not installed and no ANTHROPIC_BASE_URL set")

        if not api_key:
            log.warning(f"Missing {key_env} for model {model_name}")

        # DeepSeek API ID 直接传，不用映射（deepseek-chat 已废弃）
        kwargs = dict(
            model=model_name,
            temperature=temperature,
            max_tokens=max_tokens,
            streaming=True,
        )
        if api_key:
            kwargs["openai_api_key"] = api_key
        # base_url 优先级: 代码配置 > OPENAI_BASE_URL 环境变量（中转）> OpenAI 官方
        if not base_url and provider in ("openai",):
            base_url = os.getenv("OPENAI_BASE_URL")
        if base_url:
            kwargs["openai_api_base"] = base_url

        log.info(f"Load model: {model_name} (provider={provider})")
        return ChatOpenAI(**kwargs)
