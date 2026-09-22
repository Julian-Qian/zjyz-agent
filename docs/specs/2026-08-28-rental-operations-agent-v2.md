# Spec: 小云建材租赁经营副驾 V2

- 状态：Phase 0/1 只读纵切已在本地完成，待灰度验证
- 确认日期：2026-08-28
- 前端宿主：智建云租 React 应用（未包含）
- 后端宿主：智建云租 Spring Boot 应用（未包含）

## Implementation Status（2026-08-29）

本次已实现 Phase 0/1 的可灰度只读纵切，但尚未部署。V2 默认关闭；只有设置
`AGENT_V2_ENABLED=true`，并通过可选的 `AGENT_V2_ALLOWED_CIDS`、
`AGENT_V2_ALLOWED_UIDS` 白名单后，前端才会选择 V2，否则继续使用 V1。

当前已落地：Manifest、模型结构化 Interpreter、冻结 Scope Snapshot、受预算上下文、
Task/Run/Interaction 持久化、澄清恢复与幂等回答、短 Run 执行、取消与终态协调、
只读 Tool Adapter、保守 Evidence Gate、Task/Evidence/Tool/Artifact UI，以及 V1 兼容回退。
自然语言取消会实际取消同一企业、用户和会话中的关联任务；刷新后会恢复非终态 Run，
灰度动态关闭时会先恢复已接纳的同幂等请求，仅未接纳的新 Turn 才安全回退 V1。

本纵切有以下明确边界：

- 当前用父子 Task episode chain 表达继续、纠正和澄清；澄清续跑不重复消耗企业任务额度，
  但“同一个 Task 内多 Run”及普通 CONTINUE/CORRECT 的统一额度记账留到后续阶段。
- 经营回答只采用确定性业务 Tool 返回且带 Evidence 的事实文本；完整 Claim Ledger、
  claim-to-evidence 逐条覆盖验证和更自然的事实编排仍是 Phase 2 工作。
- `ALL` 会在提交时冻结为显式项目 ID。依赖动态 ALL 范围的应收催缴能力当前标记为不可用，
  不会偷偷扩大范围或回落旧关键词路由。
- SSE 复用现有 Run 事件流，SSE `id` 作为 Run 内序号；payload 不重复携带 `eventId/seq`。
  跨节点原子序号、heartbeat、超过 500 条的分页重放和 durable checkpoint 留到 Phase 2。
- 当前模型网关仍是同步完成后按事件块输出答案，不承诺模型 token 级真流式首字延迟。
- Phase 0/1 不执行任何业务写入、外部发送或审批动作。

## Background

当前 Agent 以关键词分类、任务帧、工具裁剪和答案模板覆盖为核心。自然语言的同义表达、追问、纠正和能力询问容易被误判；线程、业务任务和单次执行也没有清晰分层。前端主要展示聊天气泡和通用“正在工作”状态，未完整呈现澄清、证据、工具执行和产物。

建材租赁的业务本质是两条持续变化的生命周期：

1. 客户/项目 → 租出合同 → 租出 → 在租计费 → 归还/赔偿 → 结算 → 收款。
2. 供应商/同行 → 租入合同 → 租入 → 转租/调拨 → 退租 → 应付结算 → 付款。

Agent 必须围绕物资数量账、租赁费用账、客户/供应商往来账的一致性工作，而不是围绕用户是否说中预设关键词工作。

## Product Decision

小云定位为“建材租赁经营副驾”，不是通用聊天机器人或多个自治 Agent 组成的 swarm。产品采用统一内核、三个入口：

- 全局经营工作台：跨项目问答、老板/财务行动中心。
- 页面上下文助手：项目、合同、单据、库存、对账页面自动携带受信实体上下文。
- 主动行动中心：以确定性规则和业务事件发现可解释、可去重的风险事项。

统一覆盖“问、办、盯”：

- 问：查询并解释项目、合同、材料、库存、单据、结算、应收应付。
- 办：生成清单、分析、Artifact 和业务草稿；受控写入需审批。
- 盯：发现合同到期、材料未归还、长期未对账、逾期应收、待审核单据和数据异常。

## Goals

- 自然处理能力询问、闲聊、业务任务、澄清回答、追问、纠正和取消。
- 将 Thread、Turn、Task、Run 分离，使一个业务目标可以跨多轮、跨等待状态延续。
- 使用版本化 Capability Manifest 作为能力唯一事实源。
- 使用语义候选召回、受约束规划和确定性校验替代关键词路由。
- 业务数字、名单、排行和比较结论均绑定可审计 Evidence。
- 项目范围、租户、权限、会员能力和写入审批完全由服务端控制。
- 复用现有运行时、SSE、配额、权限、知识库和确定性业务服务。
- V2 可按企业/用户灰度，并可回退到 V1。

## Non-Goals

- Phase 0/1 不开放任意业务写入或外部消息发送。
- 不允许模型执行任意 SQL、Shell、脚本或任意 HTTP URL。
- 不把上传文档或知识块中的文本当成系统指令。
- 不拆分微服务，不引入多 Agent swarm 或重型编排框架。
- 不在事实源不存在时承诺利润、预测或项目级库存异常等指标。
- 不删除旧运行时表、历史会话、历史消息和历史产物。

## Assumptions

- 首个可用版本以老板、财务、项目经理和仓库角色的 READ/COMPUTE 能力为主。
- 当前 Spring Boot 单体继续作为 Agent Runtime 部署载体。
- MySQL 保持低于 8.0 的兼容要求；JSON 使用 `LONGTEXT` 并在服务层校验。
- `/agent` 继续作为统一顶级 API 前缀，不增加新的 nginx 顶级代理前缀。
- V1 与 V2 在灰度期共享身份、Workspace、Thread、Message、Artifact 和审计基础设施。

## User Scenarios

### Capability and Conversation

- 用户问“你能做什么”：Agent 从当前用户的 Capability Manifest 生成回答，不调用业务事实工具。
- 用户继续问“然后呢”：解释为继续当前 Task 或当前话题，不依赖追问词表。
- 用户说“改成上个月”：更新当前 Task 的时间约束并重新规划。

### Business Query

- 用户在企业范围问“今天最该处理哪些项目”：执行老板行动能力，返回排序、风险原因、范围、数据时间和 Evidence。
- 用户在项目详情问“还有多少材料没有归还”：页面提交受信 Project Ref，Agent 查询材料占用事实，无需重复选择项目。
- 用户问“这个月租出最多的项目”：若“最多”口径不明确，Agent 只追问数量、金额或次数这一项关键歧义。

### Controlled Action (later phase)

- 用户要求生成催缴内容：先生成 Agent Artifact 或业务草稿。
- 用户要求写入业务系统：展示范围、目标记录、参数和变更差异；明确审批后执行并写后校验。

### Failure and Recovery

- 某个事实源失败时，不把失败维度当成 0；财务事实 fail-closed，一般风险分析可明确标记 PARTIAL。
- SSE 中断后按事件序号重放；服务重启后从持久化 Checkpoint 恢复。
- V2 失败时可按灰度策略回到 V1，但同一个正在执行的 Run 不切换 Runtime。

## Target Architecture

```text
全局工作台 / 页面助手 / 行动中心
                  │
          Conversation Coordinator
        Thread / Turn / Task / Context
                  │
     Interpreter + Semantic Resolver
                  │
                 TaskSpec
                  │
  Capability Registry + Permission/Scope Filter
                  │
       Planner + Deterministic Validator
                  │
         Policy / Approval Engine
                  │
   Capability Executor → Domain Services/Ledgers
                  │
     Fact & Evidence Ledger / Claim Verifier
                  │
   Natural Response / Cards / Artifact / Actions
```

首期采用一个中央 Orchestrator 加类型化 Capability。独立只读步骤可以并行，但始终共享同一个 TaskSpec、Policy、Scope Snapshot 和 Evidence Ledger。

## Core Contracts

### ConversationInterpretation / TaskSpec

每个用户回合先产生结构化解释，再新建或更新持久化 Task：

```json
{
  "dialogueAct": "TASK | CAPABILITY_QUERY | SMALLTALK | ANSWER | CORRECTION | CANCEL",
  "taskRelation": "NEW | CONTINUE | ANSWER_PENDING | CORRECT_CURRENT | STOP | NONE",
  "goal": "找出本月仍有材料未归还且超过30天无业务活动的项目",
  "expectedOutcome": "RANKED_LIST",
  "scope": {
    "mode": "ALL | EXPLICIT | CONTEXT_ENTITY",
    "projectIds": [],
    "businessDirection": "RENT_OUT | RENT_IN | MIXED"
  },
  "entities": [],
  "constraints": {
    "timeRange": {"start": "2026-08-01", "end": "2026-08-28"},
    "asOf": "2026-08-28"
  },
  "missingInputs": [],
  "ambiguities": [],
  "risk": "READ",
  "evidenceRequirements": []
}
```

项目范围和页面实体由服务端验证并冻结，模型不能通过参数扩大访问范围。

### Capability Manifest

Manifest 是能力唯一事实源，至少包含：

- `code/version/displayName/description`
- `domains/personas/supportedScopes`
- `effect/riskLevel/requiredPermissions`
- `inputSchema/outputSchema`
- `preconditions/evidenceContract`
- `timeout/retry/idempotency`
- 自然语言示例和当前 availability

选择流程：权限、会员、范围和风险过滤 → 文本/向量混合召回 → LLM 受约束 Plan → 确定性校验。关键词只能参与召回，不能决定路由。

Capability 统一返回：

```json
{
  "status": "SUCCESS | PARTIAL | EMPTY | FAILED",
  "data": {},
  "evidence": [],
  "warnings": [],
  "freshness": {"asOf": "2026-08-28T23:15:00+08:00"},
  "suggestedNextActions": [],
  "error": {"code": null, "retryable": false, "userMessage": null}
}
```

### Task and Run

- Thread：长期会话容器。
- Turn：一次用户输入及 Agent 响应。
- Task：跨多轮、可等待澄清或审批的业务目标。
- Run：一次短暂的模型和 Capability 执行尝试。

Task 状态：

`OPEN → GATHERING_INPUT/WAITING_USER → READY → PLANNED → EXECUTING → WAITING_APPROVAL → VERIFYING → COMPLETED | BLOCKED | CANCELLED`

Run 不承载等待用户或等待审批状态。需要澄清时，本次 Run 正常结束并记录 `outcome=WAITING_USER`，Task 保持等待状态。Run 状态保持短生命周期，阶段单独记录为：

`INTERPRETING / RETRIEVING / PLANNING / EXECUTING / VERIFYING / COMPOSING`

### Evidence and Claims

数字、排行、项目名单和业务比较必须绑定 Evidence。Evidence 至少包含：

- 来源类型和脱敏来源标识。
- Scope Snapshot。
- `asOf` 或时间范围。
- 过滤条件、指标代码和指标版本。
- 记录数、完整性、checksum 和 freshness。

Guard 按 claim 验证事实；缺证据 claim 删除、降级措辞或发起澄清，不再覆盖整段自然回答。

### Memory

- 最近消息窗口。
- Task 工作记忆：目标、实体、槽位、范围、决定、待回答问题和计划版本。
- Thread 摘要：已完成事项、未决问题、用户纠正和实体引用。
- 用户显式偏好：常用项目、报表粒度、提醒阈值和表达风格。

金额、日期、审批参数和项目范围不得从模糊长期记忆自动补全。

### Approval

- READ / COMPUTE：自动执行。
- CREATE_ARTIFACT：自动生成 Agent 产物。
- CREATE_BUSINESS_DRAFT：预览，按策略审批。
- WRITE_REVERSIBLE：明确审批。
- WRITE_IRREVERSIBLE / EXTERNAL_SEND：强审批，必要时双人审批。

审批冻结参数和范围 hash；任何参数或目标版本变化都使旧审批失效。写 Capability 必须实现 `preview → approve → apply → verify`。

## Initial Capability Packs

- 项目与合同：项目检索、合同状态、项目经营快照。
- 材料与库存：租出/归还/租入/退租流水、当前占用、库存概况。
- 单据：单据检索、待审核和关联链路。
- 对账与资金：财务 KPI、应收催缴、供应商应付、对账清单。
- 风险：合同到期、长期无活动、未归还、待审核、老板行动中心。
- 计算与知识：材料估算、系统帮助、企业制度检索。

Phase 0/1 只接入现有可靠的 READ/COMPUTE 能力。

## Scope

### Frontend

- `src/api/agent.js`
- `src/store/modules/agent.js`
- `src/components/SubPage/AgentBeta/`
- 新增 Task、Interaction、Evidence、Timeline、Artifact 等可复用组件。

### Backend

- 新增 `com.zjyz.agent.workspace.v2` 下的 controller/service/model/policy/capability 模块。
- 适配现有 `AgentWorkspaceRuntimeService`、Run/Event、模型网关和确定性业务 Skill。
- 逐步旁路 `AgentIntentClassifier`、`AgentTaskFrameBuilder`、旧 Tool Catalog 路由、整段 AnswerGuard 和巨型 RunExecutor。

### Database

新增或分期新增：

- `agent_task`
- `agent_plan`
- `agent_interaction`
- `agent_approval`
- `agent_evidence`
- `agent_claim_evidence`
- `agent_memory_fact`
- `agent_run_checkpoint`
- `agent_signal`
- `agent_outbox_event`

现有 `agent_run/step/message` 增加 Task、Runtime、Phase 和计划步骤关联字段。迁移使用增量、安全、MySQL 5.7 兼容写法并以 `use zjyz;` 开头。

### Permissions

- 继续使用现有租户、用户、会员和项目范围校验。
- 模型输入中的 `cid/uid/projectIds` 不作为授权依据。
- 所有 Capability 由服务端注入经验证的 Scope Snapshot。
- Phase 0/1 无 WRITE 权限。

### Deployment and Nginx

- V2 使用 `/agent/v2`，顶级前缀仍为 `/agent`。
- 现有 nginx `/agent` 代理规则继续适用，不新增顶级 prefix。
- V1/V2 按 `cid + uid` 或功能开关灰度。

## API Contract

```text
POST /agent/v2/threads/{threadId}/turns
GET  /agent/v2/tasks/{taskId}
POST /agent/v2/tasks/{taskId}/cancel
GET  /agent/v2/threads/{threadId}/active-task
POST /agent/v2/interactions/{interactionId}/answer
GET  /agent/v2/capabilities?selectionMode=...&projectIds=...
GET  /agent/runs/{runId}/events?afterSeq=...
```

Phase 2/3 再增加 Approval、Insight 和 V2 专用 Artifact API；Phase 0/1 的 Artifact
继续复用现有 `/agent` 运行时接口。

创建 Turn 请求：

```json
{
  "clientRequestId": "uuid",
  "message": "这个项目下周要退500套，库存够不够",
  "attachmentIds": [],
  "scopeSelection": {
    "selectionMode": "EXPLICIT",
    "projectIds": ["p1"],
    "explicitOverride": false
  },
  "context": {
    "surface": "PROJECT_DETAIL",
    "entityType": "PROJECT",
    "entityId": "p1",
    "projectId": "p1"
  },
  "timezone": "Asia/Shanghai"
}
```

`ALL` 请求不携带项目 ID；服务端在提交时将其展开为当时可访问的显式项目集合并冻结。页面实体只作为提示，服务端仍需校验。成功返回 `202`，包含 `turnId/taskId/runId/streamUrl/scopeSnapshot`。

Phase 0/1 继续复用 Run SSE 地址与持久化重放。SSE envelope 的 `id` 是 Run 内序号；
V2 payload 统一包含 `schemaVersion/taskId/turnId/runId/occurredAt`，兼容字段保持在
payload 根节点。当前事件类型包括：

- `run.created/status`
- `task.resolved/status`
- `task.plan`
- `clarification.required`
- `capability.resolved`
- `knowledge.retrieved`
- `tool.started/completed`
- `artifact.created`
- `assistant.delta`
- `assistant.status/completed`
- `run.completed/failed`

`heartbeat` 和 payload 内重复的 `eventId/seq` 不属于本纵切冻结契约。

## Frontend Behavior

- 全局入口默认展示角色化行动与可用能力，不是空聊天框。
- 当前项目范围始终可见、可纠正，并与 Task Scope 一致。
- 中心区域支持自然消息、结果卡、澄清选项、审批差异卡和后续动作。
- Evidence、计划、工具时间线和 Artifact 放入右侧栏或抽屉。
- 状态只来源于 Task/Run 状态机，不同时维护互相冲突的“待命”和“发送中”。
- 显示业务含义明确的步骤，不直接暴露内部工具技术名。
- 覆盖 loading、empty、error、permission denied、partial 和 reconnecting 状态。

## Backend Behavior

- Interpreter 输出结构化对话行为和 Task 关系。
- Semantic Resolver 解析项目、方向、时间和指标版本。
- Capability Registry 先做权限/范围过滤，再做语义候选召回。
- Planner 只能使用候选 Capability；Validator 校验 Schema、依赖、范围、风险和调用上限。
- 独立 READ/COMPUTE 步骤可以并行；写步骤串行且必须幂等。
- Tool 结果标准化为 Data、Evidence、Warnings、Freshness 和 Error。
- Final Composer 只从已验证事实生成业务断言。
- 用户输入、知识块和 Tool 结果均按不可信数据处理，不泄露内部思维链。

## Migration Plan

### Phase 0: Baseline and Adapters

- Capability Manifest 和当前 Tool Adapter。
- 真实租赁任务 Eval 集。
- V2 Interpreter/Planner shadow，不执行 WRITE、不重复扣用户额度。
- 固定 REST、SSE、Card Schema 版本。

### Phase 1: Natural Read-only Agent

- Task、Interaction、Plan 和结构化上下文。
- 替换关键词分类、追问词表和整段模板 Guard。
- Manifest 驱动能力询问。
- 保守 Evidence Gate 和 React Task UI；逐 claim verifier 在 Phase 2 完成。
- 接入当前可靠的 READ/COMPUTE Skill。

### Phase 2: Multi-step Analysis and Artifacts

- Plan DAG、只读并行和有限 replan。
- 项目经营诊断、库存缺口、对账清单和结构化 Artifact。
- 页面上下文入口和企业知识融合。
- Durable checkpoint、事件序号和重启恢复。

### Phase 3: Approval-gated Business Actions

- 催缴文案/清单 → 单据草稿 → 提交审核 → 高风险财务动作。
- 参数冻结、权限复检、幂等、写后校验和审计。

### Phase 4: Proactive Collaboration

- Trigger Rule、Transactional Outbox、Signal/Insight 和行动中心。
- 订阅、静默、稍后提醒、关闭、解决和反馈闭环。

### Phase 5: Retire V1

- V2 经 shadow 和 canary 达标后成为默认。
- 已创建的 V1 Run 由 V1 执行完成。
- 留存期结束后删除旧 `/agent/chat`、关键词分类、旧 Planner/Memory 和 AnswerGuard；历史数据保留。

## Acceptance Criteria

### Safety Gates

- 跨企业或越项目范围读取为 0。
- 未经审批的受控写入为 0。
- 审批参数 hash 不一致仍执行为 0。
- 数字或名单无 Evidence 仍作为确定事实输出为 0。

### Quality

- Capability 查询从 Manifest 正确生成：100%。
- “这个/这些/然后呢/改成上个月”等 Task 关系正确率不低于 95%。
- 项目范围、时间和租入/租出方向字段准确率不低于 98%。
- Capability Recall@10 不低于 98%。
- 简单单 Capability 计划正确率不低于 95%，复杂计划不低于 90%。
- 事实 claim precision 不低于 99%，财务数字 exact match 不低于 99.5%。
- 不必要澄清率低于 10%，实质歧义漏澄清率低于 5%。
- Capability 成功时端到端任务完成率不低于 95%。

### Operations

- 分别记录首个可见状态、首 token 和任务完成 p50/p95。
- 记录每 Task 的模型调用、Capability 调用、重试和成本。
- 验证 SSE 恢复、Run 中断恢复、用户取消和 Insight 去重。
- 常规只读任务完成 P95 目标不高于 20 秒。

## Verification Plan

- Backend：目标包单元测试、`mvn -DskipTests compile` 和定向 Maven tests。
- Frontend：目标测试、`npm run build`，并手工验证 loading/empty/error/reconnect。
- API：核对 React 请求路径与 Spring mappings；运行 API prefix scanner。
- Security：租户/项目越权、Capability 伪造、提示注入、审批漂移和模型不可用测试。
- Eval：真实租赁语义、同义改写、多轮代词、纠正、租入/租出方向、业务日期、未审核单据、金额口径和部分失败。
