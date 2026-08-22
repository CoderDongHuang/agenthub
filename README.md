# AgentMesh

AgentMesh 是面向企业团队的 AI Agent 控制平面：集中管理 Agent、模型、工具、知识库、工作流、审批、审计和协作渠道。仓库同时包含可独立扩展的 Java 管理面、Python 执行面和 Vue 控制台。

> 当前定位：本机全功能 Alpha / MVP+。核心链路已经过真实 PostgreSQL、Redis、DeepSeek 和 Microsoft Edge 验证；公网高可用、企业微信回调、外部 SSO 和双地域容灾仍需要生产基础设施，不能据此宣称生产就绪。

## 核心能力

- Agent 生命周期：草稿、模型能力校验、发布、对话、停用与用量统计。
- 可靠工作流：Agent、Tool、Branch、Subflow、Approval 和 Output 节点；支持租约、重试、退避、死信与重启回收。
- RAG 与多模态：文档解析、pgvector 检索、增量索引任务、图片/音频/视频语义提取。
- 安全治理：多租户隔离、RBAC、审批卡点、护栏、审计、KMS、API Key/HMAC 和配置诊断。
- 协作渠道：钉钉 Stream、飞书和企业微信；外部账号通过持久化绑定路由到租户与 Agent。
- 平台生态：MCP、开发者网关、制品签名、供应链扫描、Kubernetes 与双地域交付模板。

## 技术栈

| 层 | 技术 |
| --- | --- |
| Web | Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router、Playwright |
| 管理面 | Java 21、Spring Boot 3、Spring Security、JDBC/JPA、Flyway、gRPC |
| 执行面 | Python 3.12、FastAPI、grpcio、Redis、httpx、pytest |
| 数据 | PostgreSQL 16、pgvector、Redis 7 |
| 模型 | DeepSeek、OpenAI、DashScope；Anthropic/Moonshot/Zhipu/Mistral 可选 |
| 交付 | Docker Compose、Nginx、Kustomize、Kubernetes、GitHub Actions |

## 架构

```text
Microsoft Edge / API / DingTalk Stream
                  |
           Vue 3 Console
                  |
       Spring Boot control plane
      /       |         |       \
PostgreSQL  Redis   gRPC/HTTP   audit
                       |
              Python Agent runtime
               /       |       \
             LLM      tools    RAG
```

## 本机启动

先从 `.env.example` 创建本机 `.env`，设置数据库、Redis、四个彼此独立的根密钥以及至少一个模型供应商 Key。真实值不得提交到 Git。

```powershell
# PostgreSQL / Redis
docker compose up -d

# Python Runtime
cd python-engine
.\.venv\Scripts\python.exe main.py

# Java control plane
cd ..\java-console
mvn spring-boot:run

# Web console
cd ..\web-ui
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。本机演示账号为 `admin / admin123`，仅限本机开发环境。

## 验证

```powershell
cd java-console
mvn test -q

cd ..\python-engine
.\.venv\Scripts\python.exe -m pytest -q

cd ..\web-ui
npm run build
npm run test:e2e

cd ..
.\python-engine\.venv\Scripts\python.exe scripts\verify_redis_sessions.py
```

`npm run test:e2e` 使用系统 Microsoft Edge，并直接访问本机真实 Java/Python/PostgreSQL/Redis 服务，不拦截 API。运行前应确保三端和数据服务均已启动。

## 文档

- [项目现状与改进路线](docs/项目现状与改进路线.md)
- [全链路验收与配置清单](docs/全链路验收与配置清单.md)
- [架构设计](docs/架构设计.md)
- [开发者指南](docs/developer-guide.md)
- [渠道与回调配置](docs/channel-callback-setup.md)
- [实施计划](docs/实施计划.md)

## 生产边界

`agentmesh.asia` 已注册，但在公网负载均衡、服务器或托管入口和 TLS 证书就绪前不创建 A/CNAME。企业微信要求企业主体可验证的 HTTPS 回调域名；外部 OIDC/SAML、托管 PostgreSQL/Redis、Kubernetes 双地域部署和恢复压测也需在目标生产环境完成。

## License

[MIT](LICENSE)
