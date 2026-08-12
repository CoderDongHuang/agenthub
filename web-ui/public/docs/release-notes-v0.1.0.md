# AI Agent Hub v0.1.0 Release Notes

企业级 AI Agent 中台首个公开版本。业务人员 3 分钟创建 Agent，IT 统一管控，合规可审计。

## 控制台与运行态势更新

- 新增 Java `GET /api/platform/overview` 聚合接口
- 新增 Python `GET /runtime/capabilities` 安全能力快照
- 新增 JWT 会话恢复接口 `GET /api/auth/me`
- 重构公开官网、登录注册和全部控制台工作页面
- Agent 详情、对话、知识索引、审批决策和审计追踪采用独立工作流布局

---

## 核心能力

### Agent 生命周期管理
- 表单创建 Agent：选模型、写提示词、调温度
- 发布 / 停用 / 编辑，状态流转
- 支持 DeepSeek V3/R1、GPT-4o、Claude、通义千问

### 流式对话 + 工具调用
- SSE 实时推送，逐词显示
- ReAct 循环：LLM 推理 → 工具调用 → 观察 → 回复
- 内置工具：计算器、日期时间、网络搜索

### 审批卡点（核心差异化）
- 工具分 low / medium / high 三级
- medium → 单人审批，high → 双人审批
- 审批通过前工具不执行，实现事前阻断

### 审计日志
- 对话、审批、创建等操作全量记录
- 按事件类型/用户/时间筛选
- `@Auditable` AOP 注解自动记录

### 工具生态
- 开发者写 Python 文件丢到 `custom/` 目录
- 启动时自动发现 → 注册 → 同步到工具市场
- 支持限流、超时、风险等级配置

### RAG 知识库
- 上传/粘贴文档 → 自动分块 → TF-IDF 向量化
- 对话时自动检索相关知识注入 Prompt
- 也可接入 OpenAI Embedding

### 多渠道接入
- Web 控制台（Vue 3）
- REST API（API Key 认证）
- 网页嵌入（一行 `<script>`）
- 渠道适配器：企微/钉钉/飞书（接口就绪，待配置回调）

### 国际化
- 默认中文，右上角一键切换 English
- Element Plus 组件跟随切换

---

## 技术栈

| 层 | 技术 |
|----|------|
| 前端 | Vue 3 + Vite + Element Plus + Pinia |
| 管理面 | Spring Boot 3.3 + JPA + Flyway + gRPC Server |
| 运行时 | Python + FastAPI + LangChain + gRPC Client |
| 通信 | gRPC 双向流（Java ↔ Python） |
| 存储 | PostgreSQL 16 + pgvector + Redis 7 |
| LLM | LiteLLM 统一适配多模型 |

---

## 快速开始

```bash
# 1. 配置 API Key
cp .env.example .env   # 填入 DEEPSEEK_API_KEY

# 2. 启动数据库
docker-compose up -d

# 3. 启动 Python 引擎
cd python-engine && python main.py

# 4. 启动 Java 控制台
cd java-console && ./mvnw spring-boot:run

# 5. 启动前端
cd web-ui && npm install && npm run dev
```

打开 `http://localhost:5173`，默认账号 `admin/admin123`。

---

## 路线图

| 版本 | 计划 |
|------|------|
| v0.1.0 | ✅ 本次发布：Agent 管理 + 对话 + 审批 + 审计 + 工具市场 + RAG |
| v0.2.0 | 企微/钉钉/飞书真实对接、Flowable 审批引擎 |
| v0.3.0 | 多租户完善、第三方工具市场、WebSocket 推送 |
| v1.0.0 | 生产就绪、性能压测、集群部署方案 |
