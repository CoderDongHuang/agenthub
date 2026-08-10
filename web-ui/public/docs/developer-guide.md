# 开发者指南 — 自定义工具开发

开发者只需写一个 Python 文件放到指定目录，平台自动识别、注册、同步到工具市场。Agent 创建时就能勾选使用。

---

## 快速开始（30 秒）

1. 复制 `python-engine/agent_runtime/tools/custom/__template__.py`
2. 改文件名，填业务逻辑
3. 重启 Python Engine → 工具上线

---

## 工具结构

```python
# crm_query.py — 放到 tools/custom/ 目录下
from agent_runtime.tools.base import AgentTool, tool

@tool(
    name="crm_query",              # 工具名，只允许英文/数字/下划线/连字符
    description="根据手机号查询CRM中的客户信息",
    risk_level="medium",           # low=直接执行 | medium=单人审批 | high=双人审批
    rate_limit=100,                 # 每天最多调用次数（0=不限）
    timeout=10,                     # 超时秒数
    category="CRM",                 # 在工具市场中的分类
)
class CRMQueryTool(AgentTool):

    async def execute(self, phone: str) -> str:
        """
        Args:
            phone: 客户手机号，11位
        Returns:
            客户信息文本
        """
        # 👇 你的业务逻辑写在这里
        customer = await your_crm_api.query(phone)
        return f"姓名: {customer.name}, 等级: {customer.level}, 累计金额: {customer.amount}"
```

---

## `@tool` 装饰器参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `name` | str | 必填 | 工具唯一名，正则 `^[a-zA-Z0-9_-]+$` |
| `description` | str | 必填 | 描述会传给 LLM 作为 function description |
| `risk_level` | str | `"low"` | `low`=自动执行, `medium`=单人审批, `high`=双人审批 |
| `rate_limit` | int | `0` | 每天调用上限，0=不限 |
| `timeout` | int | `30` | 超时秒数，超时自动 kill |
| `category` | str | `"自定义"` | 工具市场的分类标签 |

## `execute()` 方法规则

- 必须是 `async def`
- 参数自动提取为 JSON Schema（供 LLM function calling 使用）
- 用类型标注：`str`、`int`、`float`、`bool`
- 返回值必须是 `str` — 会给 LLM 看
- 抛异常会被捕获并友好提示用户

## 风险等级与审批流程

```
risk_level="low"     → 🟢 大模型直接调用，无需审批
risk_level="medium"  → 🟡 自动创建审批单，通过后才执行
risk_level="high"    → 🔴 需要双人审批
```

管理员可在审批中心配置具体策略。

## 自动发现机制

Python Engine 启动时自动扫描 `tools/custom/` 目录：

1. 找到所有带 `@tool` 装饰器的类
2. 注册到工具执行器中
3. 同步到 Java Console → 出现在工具市场
4. LLM 即可通过 function calling 调用

## API 接入

```bash
# 通过 API Key 调用 Agent
curl -X POST http://localhost:8080/api/v1/chat \
  -H "X-API-Key: ak-dev-0000" \
  -H "Content-Type: application/json" \
  -d '{"agentId":1, "message":"查一下手机号13800138000的客户信息"}'

# SSE 流式调用
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H "X-API-Key: ak-dev-0000" \
  -d '{"agentId":1, "message":"帮我查一下"}'
```

## 网页嵌入

任意网页加一行 `<script>` 即可嵌入 Agent 对话：

```html
<script src="http://localhost:5173/embed.js"
        data-agent-id="1"
        data-api-key="ak-dev-0000"></script>
```

## 整体架构

```
开发者写 tool.py → 丢进 custom/ 目录
  → Python 引擎自动发现 + 注册
  → 同步到 Java DB（tool_definition 表）
  → 出现在前端「工具市场」
  → 用户创建 Agent 时勾选此工具
  → LLM 通过 function calling 调用
  → 风险检查 → 需要审批？→ 执行
  → 结果返回 LLM → 用户看到回复
```

## 对接的大模型

| 模型 | 需设置的环境变量 |
|------|----------------|
| DeepSeek V3 / R1 | `DEEPSEEK_API_KEY` |
| GPT-4o / GPT-4o-mini | `OPENAI_API_KEY` |
| Claude Sonnet / Opus | `ANTHROPIC_API_KEY` |
| 通义千问 Max / Plus | `DASHSCOPE_API_KEY` |

配置方式：复制 `.env.example` 为 `.env`，填入你的 Key 即可。
