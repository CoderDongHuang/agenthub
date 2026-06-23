"""
LLM 客户端封装 — 通过 LiteLLM 统一适配多模型
"""
import logging

log = logging.getLogger(__name__)


class LLMClient:
    """多模型 LLM 客户端（Phase 1 实现完整逻辑）"""

    def __init__(self):
        self._models = {}

    def get_model(self, model_name: str):
        """
        根据模型名获取 LangChain ChatModel

        支持的模型:
        - gpt-4o / gpt-4o-mini
        - claude-sonnet-4-6 / claude-opus-4-8
        - deepseek-v3 / deepseek-r1
        - qwen-max / qwen-plus
        """
        if model_name not in self._models:
            # Phase 1 实现: 通过 LiteLLM 创建对应的 ChatModel
            log.info(f"创建模型实例: {model_name}")
            # TODO: 实际模型创建
        return self._models.get(model_name)
