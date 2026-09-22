# Spec: 智建云租项目级 Agent Workspace 与 Skill Learning Engine

- Status: Approved
- Approved at: 2026-08-16
- Phase in implementation: Phase 0/1
- Frontend: React + Redux + Ant Design
- Backend: Spring Boot 2.7 + Java 11 + MyBatis Plus + MySQL
- Primary model: GLM-5.2
- Future knowledge stack: Embedding-3 (1024 dimensions) + Qdrant

## Background

现有 Agent-Beta 只有 `/agent/chat` 和 `/agent/health`，通过规则 Intent 将一次请求交给一个 Skill Handler；Thread、Run、Step 和对话记忆没有可靠持久化，也没有连续 Tool Calling、SSE 事件、取消、审批、Skill 版本和知识隔离。

本功能将 Agent 升级为项目级工作台：用户选择业务项目后，在固定 `cid + projectId` 上下文中持续工作。Workspace 属于项目，Thread 默认属于创建人；模型不能修改服务端绑定的企业、用户和项目范围。

## Goals

Phase 0/1：

- 项目 Workspace、私有 Thread、消息和 Run 持久化。
- GLM-5.2 多轮 Tool Calling 与流式响应。
- 将六个现有 Skill Handler 包装为受控业务 Tool。
- 支持 Run/Step/ToolCall/Event、取消、断线补发、Evidence 和 Artifact。
- 通过 `AuthContext`、项目归属和服务端 ToolContext 保证租户与项目隔离。
- 使用后端 capability 和 `feature_account_whitelist` 取代前端 development-only 入口。
- 保留旧 `/agent/chat` 与项目经营报告使用的 `AgentLlmClient`。

后续阶段：

- Project Knowledge、Thread Summary、Project Memory。
- Hermes 式 Skill Learning、验证、Dry Run、审批、发布、回滚和 Skill Studio。
- 经独立补充 Spec 才能开放草稿写入或正式业务副作用。

## Non-goals

- Phase 0/1 不执行任意 Shell、SQL、脚本或任意 HTTP URL。
- 不自动提交或修改租出、归还、赔偿、租入、租入退租、对账/结算正式单据。
- 不改变单据审核、反审核、库存、结算和计价规则。
- 不保存或展示模型原始思维链。
- 不直接使用 Hermes 作为生产业务后端。
- 首发不引入 Redis、Kafka 或多 Agent 调度。

## Scope

### Document scope

Scope: cross-document, read-only。

`document.search` 可审计/检索租出单、归还单、赔偿单、租入单、租入退租单及对账/结算单；仅复用现有只读服务和下载能力，不改变任何业务单据实现。

### Frontend

- 替换 Agent-Beta 的单会话体验为三栏 Workspace。
- 左栏：项目上下文、Thread 新建/选择/重命名/归档。
- 中栏：消息、Run 状态、Tool 状态、SSE 回答、取消和重试。
- 右栏：项目、Evidence、Tools、Artifact 和配额。
- 使用 `fetch + ReadableStream` 消费 Bearer-authenticated SSE。

### Backend

- Workspace、Thread、Message、Run、Step、ToolCall、Event、Artifact。
- `AgentModelGateway` 与 `GlmChatAdapter`，不扩大旧 `AgentLlmClient` 职责。
- Tool Registry、ToolContext、参数 Schema、风险等级和执行限制。
- MySQL 持久化事件；单实例有界后台 Executor。

### API and nginx

保持 `/agent` 顶级前缀：

- `/agent/capabilities`
- `/agent/workspaces`
- `/agent/threads`
- `/agent/runs`
- `/agent/artifacts`

生产 nginx 两个 HTTPS server block 必须覆盖 `/agent`。SSE 需要关闭 buffering/cache，并提高 read/send timeout。

## Architecture

```text
React Agent Workspace
  -> REST + authenticated SSE
Spring Boot Agent Control Plane
  -> Workspace / Thread / Run / Event / Quota / Policy
Agent Runtime
  -> Context Builder -> GLM -> Tool Calls -> Policy -> Tools -> GLM
Business Tools
  -> Project / Inventory / Documents / Rent Materials / Estimate / Help
```

## Runtime rules

Run 状态：

```text
QUEUED -> PLANNING -> RUNNING
                     -> WAITING_USER
                     -> WAITING_APPROVAL
                     -> COMPLETED | FAILED | CANCELLED | INTERRUPTED
```

- 单用户最多 2 个活动 Run。
- 单 Run 最多 12 次模型迭代、20 次 Tool Call，默认 120 秒。
- 只读工具明确失败时最多重试一次；任何写工具响应不确定时不得自动重试。
- 服务重启后遗留活动 Run 标记为 `INTERRUPTED`。
- UI 只显示阶段摘要，不存储或展示 `reasoning_content`。

## Model layer

首发使用已提供正式 API 的 GLM-5.2。模型名、base URL、推理强度、超时和最大迭代均配置化；GLM-5.3 需等模型 API 正式开放并通过同一评测集后灰度切换。

新网关支持完整 messages、tool definitions/tool calls、SSE、结构化输出、usage 和 retry。GLM 不可用时只允许降级到现有确定性 Planner 的单工具只读能力。

## Tool layer

风险等级：

```text
READ / COMPUTE / DRAFT_WRITE / COMMIT_WRITE / EXTERNAL_SIDE_EFFECT
```

Phase 0/1 只启用 READ 与 COMPUTE：

| Tool code | Existing implementation | Risk |
| --- | --- | --- |
| `project.get_summary` | `ProjectSummarySkillHandler` | READ |
| `inventory.get_summary` | `InventorySummarySkillHandler` | READ |
| `document.search` | `DocumentSearchSkillHandler` | READ |
| `project.list_rent_materials` | `ProjectRentMaterialsSkillHandler` | READ |
| `material.estimate` | `MaterialEstimateSkillHandler` | COMPUTE |
| `help.search` | `HelpKnowledgeSkillHandler` | READ |

`cid/uid/projectId/projectBusinessType/workspaceId/threadId/runId/traceId` 由服务端 ToolContext 注入；模型参数不能覆盖这些值。

## Data model

Phase 0/1 新表：

- `agent_workspace`
- `agent_thread`
- `agent_message`
- `agent_run`
- `agent_step`
- `agent_tool_call`
- `agent_artifact`
- `agent_run_event`

复用 `agent_usage_record`。所有迁移以 `use zjyz;` 开始，使用 `utf8mb4`，保持 MySQL 8.0 以下兼容；结构化扩展字段使用 `LONGTEXT` 并由应用层校验。

后续表：`agent_approval`、`agent_model_call`、Knowledge/Memory/Skill 相关表。

## API contract

普通请求沿用 `{succeed,data,errorCode,errorMessage}`。创建 Run 使用 `clientRequestId` 幂等并返回 HTTP 202。

```text
GET    /agent/capabilities?projectId=
POST   /agent/workspaces
GET    /agent/workspaces/{workspaceId}
GET    /agent/workspaces/{workspaceId}/threads
POST   /agent/workspaces/{workspaceId}/threads
PATCH  /agent/threads/{threadId}
DELETE /agent/threads/{threadId}
GET    /agent/threads/{threadId}/messages
POST   /agent/threads/{threadId}/runs
GET    /agent/runs/{runId}
GET    /agent/runs/{runId}/events?afterSeq=
POST   /agent/runs/{runId}/cancel
GET    /agent/threads/{threadId}/artifacts
GET    /agent/artifacts/{artifactId}
```

SSE events：`run.created`、`run.status`、`assistant.status`、`assistant.delta`、`tool.requested`、`tool.started`、`tool.completed`、`artifact.created`、`run.completed`、`run.failed`、`heartbeat`。

## Skill Learning target design

Agent 只能生成 Knowledge/Workflow Skill 草稿，不能生成 Tool 或 Native Skill。Skill 作用域为 BUILTIN/TENANT/PROJECT/PRIVATE，版本不可变，状态为 DRAFT/VALIDATING/READY_FOR_REVIEW/PUBLISHED/DISABLED/ARCHIVED/VALIDATION_FAILED。

DSL 只允许 ASK_USER、CALL_TOOL、CONDITION、CHECK、COMPOSE；禁止脚本、SQL、任意 URL、未注册 Tool、权限提升、修改系统 Prompt 和绕过审批。缺少能力时生成 ToolGapProposal。

流程：教学来源 -> Draft -> Schema/security validation -> historical dry run -> tests -> human approval -> immutable publish -> feedback/revision。不得自动发布。

## Security

- 每次访问重新校验 `AuthContext`、cid、project ownership、thread owner。
- Tool/Knowledge 输出按不可信数据处理，不能覆盖系统 Policy。
- 不向模型传输密钥、JWT、密码和未脱敏敏感字段。
- 日志记录 trace/run/model/tool/status/latency/token/cost，不记录思维链或凭证。
- 动态 Skill 不能增加 Tool 权限。

## Acceptance criteria

- Workspace 按 `cid + projectId` 隔离，Thread 按 owner 隔离。
- 刷新和重新登录后 Thread、消息和 Run 仍存在。
- GLM 可在一次 Run 中连续调用多个受控 Tool。
- 模型不能修改服务端项目上下文。
- SSE 支持 Bearer、顺序事件和 `afterSeq` 补发。
- 用户可取消 Run；重启后遗留 Run 不会永久停留在 RUNNING。
- 所有业务 Tool 有 Evidence、traceId 和审计记录。
- 跨租户、跨项目、跨用户 Thread 测试全部拦截。
- 旧 `/agent/chat`、项目经营报告和现有单据规则无回归。
- 前后端构建通过，生产 `/agent` 返回 API JSON/SSE 而非 React HTML。

## Implementation order

1. Runtime migration and persistence entities/mappers.
2. Workspace/Thread APIs and authorization.
3. Run/Event/SSE and bounded executor.
4. GLM model gateway and Tool Registry adapters.
5. React API/store/workspace UI.
6. Backend compile/tests, frontend build, API prefix scan and nginx/deployment notes.
7. Knowledge/Memory and Skill Learning remain separate later phases.
