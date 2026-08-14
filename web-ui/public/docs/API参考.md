# API 参考

Java 管理面默认地址为 `http://localhost:8080/api`。控制台接口使用 HttpOnly 认证 Cookie；外部 Agent API 使用 `X-API-Key`；Java-Python 内部接口使用内部服务凭据。

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

登录会设置 `AGENTHUB_AUTH` HttpOnly Cookie。写请求还需先获取 CSRF Token：

```http
GET /api/auth/csrf
Cookie: AGENTHUB_AUTH=<由登录响应设置>

X-XSRF-TOKEN: <响应 data.token>
```

## Agent

```http
GET    /api/agents?size=50&sort=updatedAt,DESC
POST   /api/agents
GET    /api/agents/{id}
PUT    /api/agents/{id}
DELETE /api/agents/{id}
PUT    /api/agents/{id}/publish
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
```

分块写入属于 `/api/internal/knowledge/**` 内部同步接口，不向浏览器公开。

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

## 外部 API Key 示例

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "X-API-Key: ak-your-key" \
  -H "Content-Type: application/json" \
  -d '{"agentId":1,"message":"只回复 OK"}'
```

控制台自动处理 Cookie 和 CSRF。命令行调用控制台写接口时，应使用 Cookie Jar 保存登录 Cookie，并把 `/api/auth/csrf` 的 `data.token` 放入 `X-XSRF-TOKEN`。

## 错误处理

| code | 含义 |
| --- | --- |
| 200 | 成功 |
| 400 | 参数或配置错误 |
| 401 | 未登录或 Token 无效 |
| 403 | 权限不足 |
| 404 | 资源不存在 |
| 502 | 外部模型或渠道调用失败 |

