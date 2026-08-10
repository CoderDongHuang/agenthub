# API 参考

Java 管理面默认地址为 `http://localhost:8080/api`。除登录和内部接口外，请求需要 JWT。

## 统一响应

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

注意：业务错误可能通过 `code` 返回，客户端应同时检查 HTTP 状态和响应体 `code`。

## 登录

```http
POST /api/auth/login
Content-Type: application/json

{"username":"admin","password":"admin123"}
```

后续请求：

```http
Authorization: Bearer eyJ...
```

## Agent

```http
GET    /api/agents?size=50&sort=updatedAt,DESC
POST   /api/agents
GET    /api/agents/{id}
PUT    /api/agents/{id}
DELETE /api/agents/{id}
POST   /api/agents/{id}/publish
POST   /api/agents/{id}/chat
```

创建 Agent：

```json
{
  "name": "合同审阅助手",
  "description": "定位合同风险并给出修改建议",
  "systemPrompt": "只依据合同原文回答，并标注条款位置。",
  "model": "deepseek-chat",
  "temperature": 0.2,
  "maxTokens": 4096
}
```

## 知识库

```http
GET    /api/knowledge/docs
POST   /api/knowledge/upload
POST   /api/knowledge/text
GET    /api/knowledge/docs/{id}
DELETE /api/knowledge/docs/{id}
GET    /api/knowledge/docs/chunks
POST   /api/knowledge/docs/{id}/chunks
```

Python 运行时：

```http
POST   http://localhost:8000/rag/index?doc_id={id}
DELETE http://localhost:8000/rag/docs/{id}
GET    http://localhost:8000/rag/stats
```

## 工作区资源

资源类型支持 `workflow`、`guardrail`、`channel`、`routing`。

```http
GET    /api/workspace/{type}
POST   /api/workspace/{type}
GET    /api/workspace/{type}/{id}
PUT    /api/workspace/{type}/{id}
DELETE /api/workspace/{type}/{id}
```

流程：

```http
POST /api/workspace/workflow/{id}/run
GET  /api/workspace/workflow/{id}/executions
```

护栏与渠道：

```http
POST /api/workspace/guardrail/test
POST /api/workspace/guardrail/publish
POST /api/workspace/channel/{id}/test
```

## 用量与预算

```http
GET /api/billing/usage
GET /api/billing/budget
PUT /api/billing/budget
```

更新预算：

```json
{
  "month": "2026-07",
  "totalBudget": 1500,
  "alertThreshold": 0.8
}
```

## curl 完整示例

```bash
TOKEN=$(curl -s http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jq -r '.data.token')

curl http://localhost:8080/api/billing/usage \
  -H "Authorization: Bearer $TOKEN"
```

## 错误处理

| code | 含义 |
| --- | --- |
| 200 | 成功 |
| 400 | 参数或配置错误 |
| 401 | 未登录或 Token 无效 |
| 403 | 权限不足 |
| 404 | 资源不存在 |
| 502 | 外部模型或渠道调用失败 |

