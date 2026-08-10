# 多 Agent 编排指南

流程编排用于把多个 Agent、工具、判断和人工审批组织成可重复执行的业务过程。

## 节点类型

| 类型 | 作用 | 关键配置 |
| --- | --- | --- |
| entry | 接收业务输入 | 输入 Schema、渠道 |
| agent | 进行理解或生成 | Agent ID、提示词、超时 |
| tool | 调用业务能力 | 工具名、参数映射、风险等级 |
| approval | 暂停并等待人工决定 | 审批人、超时、驳回路径 |
| output | 返回或分发结果 | 输出格式、目标渠道 |

## 保存格式

流程保存在 `workspace_resource.config` 的 JSONB 字段中：

```json
{
  "version": 4,
  "nodes": [
    {"id": 1, "type": "entry", "title": "客户请求", "x": 40, "y": 135},
    {"id": 2, "type": "agent", "title": "材料分析", "agentId": 3, "x": 250, "y": 60},
    {"id": 3, "type": "approval", "title": "金额复核", "x": 475, "y": 60}
  ]
}
```

工作台点击“保存草稿”时调用：

```http
PUT /api/workspace/workflow/{id}
Content-Type: application/json
```

## 运行与审批卡点

点击“运行流程”会创建 `workspace_execution`。流程遇到 `approval` 节点时状态变为 `waiting_for_approval`，不会继续执行后续高风险步骤。

```bash
curl -X POST http://localhost:8080/api/workspace/workflow/1/run \
  -H "Authorization: Bearer $TOKEN"
```

查询最近 20 次执行：

```bash
curl http://localhost:8080/api/workspace/workflow/1/executions \
  -H "Authorization: Bearer $TOKEN"
```

## 节点失败策略

推荐支持三类策略：

- `stop`：立即停止并通知负责人，适合不可逆动作。
- `retry`：对明确可重试的网络错误重试，必须有次数和退避上限。
- `manual`：转人工处理，保留输入、错误和已完成步骤。

模型拒答、参数校验失败通常不可盲目重试。HTTP 429、短暂网络故障可以指数退避。

## 设计一个理赔流程

1. 入口校验案件号和材料列表。
2. 材料 Agent 提取关键信息并标注证据位置。
3. 条款 Agent 根据知识库判断保障范围。
4. 金额超过阈值时进入人工审批。
5. 审批通过后调用赔付工具。
6. 输出 Agent 生成面向客户的解释。
7. 全链路写入审计日志。

## 生产要求

- 每个流程版本不可变，修改产生新版本。
- 节点输入输出使用 JSON Schema，不依赖自然语言约定。
- 工具节点必须携带幂等键。
- 审批恢复后从卡点继续，不重复执行已完成副作用。
- 执行记录包含 trace ID，便于跨 Java、Python 和外部系统排查。

