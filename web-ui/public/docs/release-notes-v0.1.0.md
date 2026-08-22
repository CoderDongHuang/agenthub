# AgentMesh v0.1.0 Release Notes

发布日期：2026-08-22

AgentMesh v0.1.0 提供可在本机完整运行的企业 AI Agent 控制平面。此版本的发布范围是 Alpha / MVP+，不是公网生产 SLA 承诺。

## 主要能力

- Vue 3 控制台覆盖 Agent、工具、知识库、工作流、审批、审计、渠道、治理、生态和运维。
- Spring Boot 管理面提供多租户、RBAC、CSRF/JWT、Flyway、审计和 gRPC 服务。
- Python Runtime 提供模型调用、工具执行、RAG、Redis 会话和钉钉 Stream 客户端。
- PostgreSQL/pgvector 与 Redis 承载资源、任务、索引、会话和执行状态。
- 工作流持久化 worker 支持 Agent、Tool、Branch、Subflow、Approval、Output、租约、重试和死信。
- 渠道回调支持签名/解密、租户/Agent 绑定、幂等入队和异步处理。
- Agent 发布前校验 Runtime 的真实供应商和模型目录。
- 关键运营结果采用结构化展示，正文与控件字号不低于 12px。

## 真实验收

- Java：51 tests，0 failures，0 errors。
- Python：25 passed。
- Web：类型检查与生产构建通过。
- Flyway：真实 PostgreSQL 迁移至 V21。
- Redis：真实并发追加无消息覆盖。
- Tool 工作流：Execution 16 完成，calculator 输出 `42`。
- DeepSeek 工作流：Execution 17 完成，真实模型输出 `LIVE_AGENT_OK`。
- Microsoft Edge：桌面/移动、登录、控制台和真实工作流 E2E；最终 Execution 20 为 `completed`。

## 渠道与域名

- 钉钉企业内部应用和机器人 `AgentMesh` 已创建发布，使用 Stream 模式；robotCode 为 `dingck36tmfhta4qbvko`。
- 飞书和企业微信凭证已保存在本机环境；公网 HTTP 回调等待正式 TLS 入口。
- `agentmesh.asia` 已注册并委托阿里云 DNS；在获得真实公网目标前不创建 A/CNAME。

## 已知边界

- 本次钉钉 Stream 可以获得官方 endpoint，但当前网络出口的 WebSocket opening handshake 超时并自动重连，需在稳定生产网络复验消息收发。
- 企业微信需要企业主体可验证的 HTTPS 域名。
- 外部 SSO、生产 PostgreSQL/Redis、真实双地域 Kubernetes、灾备切换和容量压测尚未完成。
- Anthropic、Moonshot、Zhipu、Mistral 是按需可选供应商，当前未配置。

完整状态见《项目现状与改进路线》和《全链路验收与配置清单》。
