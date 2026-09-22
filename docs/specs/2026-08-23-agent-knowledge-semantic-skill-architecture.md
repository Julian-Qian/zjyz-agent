# Spec: Agent 知识、语义与 Skill 三层架构

- 状态：已批准
- 批准日期：2026-08-23
- 实施范围：智建云租 Agent 工作台（前端与后端）
- 实施方式：分阶段兼容演进，不替换现有 `/agent` 工作台协议

## Background

当前 Agent 已具备 GLM 工具调用、工作空间、会话、Run、Step、ToolCall、事件流和产物能力，但业务能力仍主要依靠逐个编写 Skill 和关键词意图分类。其直接问题是：

- 新问题必须补充新 Skill，能力扩展速度慢；
- 模型不了解业务术语、数据口径和已批准规则；
- 连续对话会把上一轮任务错误带入新问题；
- 工具描述、参数、适用范围和执行逻辑集中在一个注册表中；
- 回答虽然可附工具证据，但缺少知识来源、任务理解和回答守卫审计。

## Goals

- 建立“知识与业务语义层—通用执行工具层—Skill/工作流层”三层架构。
- 每轮消息生成独立 Task Frame，明确任务、领域、时间和项目选择范围。
- 仅检索可信、已批准、可追溯的知识，不把仓库全部 Markdown 直接交给模型。
- 业务事实必须来自服务端校验过的确定性工具结果。
- 让多数只读查询通过知识检索与通用工具组合完成，减少一问一 Skill。
- 为后续 Hermes 式 Skill 教学、验证、版本化和发布保留扩展点。

## Non-Goals

- 第一阶段不开放写操作、任意 SQL、Shell、URL 请求或模型自定义 Java 代码。
- 第一阶段不接入 Qdrant，不建立自动向量化流水线。
- 第一阶段不允许模型自动发布 Skill。
- 第一阶段不重写已有材料预估、项目经营报告和应收催缴核心业务算法。
- 本次不改变 nginx 顶级代理前缀；所有增量能力继续使用 `/agent`。

## Architecture

### 1. 知识与业务语义层

知识分为产品帮助、业务术语、数据语义、指标定义、能力提示和安全策略。每条知识至少包含：

- `id`、`title`、`type`、`domain`；
- `authority`、`status`、`effectiveDate`、`source`；
- `content`、关键词/别名、建议工具；
- 返回模型和前端的可审计引用信息。

来源权威顺序为：

1. 当前运行代码与实时业务数据；
2. 已批准且已经实现的 Spec；
3. 已批准待实施的 Spec；
4. 已发布帮助中心；
5. 历史参考资料；
6. AI Prompt、临时速查表和未批准草稿不得成为业务事实来源。

第一阶段使用内置、人工审核的结构化知识包和关键词/别名检索；后续使用 Embedding-3（1024 维）与 Qdrant，并保留相同引用协议。

### 2. 通用执行工具层

工具必须使用严格 JSON Schema，并由服务端注入和复核 `cid`、`uid`、项目选择范围。模型不得传入或覆盖安全上下文。

目标工具族包括：

- `knowledge.search`
- `project.search`
- `contract.search`
- `document.search`
- `settlement.cycle_query`
- `receivable.query`
- `inventory.query`
- `material.search`
- `project.operating_snapshot`
- `material.estimate`

通用数据工具逐步统一输出 `ToolResultEnvelope`：

```json
{
  "schemaVersion": "1.0",
  "datasetCode": "project.search",
  "scope": {},
  "filters": {},
  "summary": {},
  "rows": [],
  "warnings": [],
  "evidence": {}
}
```

工具目录负责工具名称、用途、风险等级、参数 Schema、适用选择范围和模型可见性；工具执行注册表只负责权限复核和调用业务服务。

### 3. Skill/工作流层

保留原生 Skill 处理高价值、强口径或多步骤流程，例如应收催缴、材料预估、项目经营报告及未来写操作。

可教学 Skill 使用受限 DSL，仅允许：

- `ASK_USER`
- `CALL_TOOL`
- `CONDITION`
- `CHECK`
- `COMPOSE`

后续 Hermes 式教学流程为：成功 Run 转草稿 → Schema/权限校验 → 测试用例与 Dry Run → 人工批准发布 → 不可变版本与回滚。没有底层能力时仅创建 `ToolGapProposal`，不得生成任意执行代码。

## Runtime Flow

每轮请求按以下顺序执行：

1. 服务端重校验企业、用户与项目选择范围；
2. 从最新用户消息生成 Task Frame；
3. 判断是否为真正追问，非追问不注入上一轮原始回答；
4. 检索可信知识并记录引用；
5. 仅向模型暴露与本轮任务相关且有权限的 3～6 个工具；
6. 模型规划并调用工具；
7. 汇总事实账本；
8. 回答守卫检查需要业务事实的问题是否存在工具证据；
9. 把 Task Frame、知识引用、事实和守卫结果写入消息审计元数据。

Task Frame 字段为：

```json
{
  "taskType": "QUERY|SUMMARY|HELP|ESTIMATE",
  "domain": "PROJECT|SETTLEMENT|FINANCE|INVENTORY|MATERIAL|DOCUMENT|HELP|GENERAL",
  "userGoal": "本轮用户原始目标",
  "timeRange": "明确或推断出的时间口径",
  "selectionMode": "ALL|EXPLICIT",
  "followUp": false,
  "requiresBusinessData": true
}
```

## Business Semantics

- “某月应该对账”：合同结算周期的周期结束日落在目标月份，并且不存在覆盖该周期的有效财务对账单。
- “截至某月末仍未对账”：截至目标月末仍存在未被有效财务对账单覆盖的结算区间；它与“某月应该对账”是两个不同数据集。
- “项目录入时间”使用项目正式记录的创建日期；不得把对账、单据或最近操作日期冒充项目录入日期。
- 企业全局信息通过项目选择范围“全部项目”表达，不再区分企业经营空间和项目空间。
- 财务金额、项目数量、单据状态、库存数量等事实必须来自确定性工具，知识仅解释口径和指导选工具。

## Scope

- 前端：`src/components/SubPage/AgentBeta`，展示任务理解、知识依据、工具证据和产物。
- 后端：`com.zjyz.agent.workspace` 下新增 context、knowledge 和 tool catalog 分层，并接入 Run Executor。
- 数据库：第一阶段复用 `agent_message.metadata_json`，不新增迁移；动态知识与 Skill Studio 表留在后续阶段。
- 权限：保持现有 Agent 白名单、财务白名单和服务端项目范围复核。
- API：兼容现有 `/agent` 接口；capabilities 增加知识/语义/教学能力标志；SSE 可增加知识检索事件。
- nginx：无新顶级前缀，现有 `/agent/` 规则继续适用。

## API Contract

### `GET /agent/capabilities`

兼容新增字段：

- `knowledgeEnabled: boolean`
- `knowledgeMode: string`
- `semanticQueryEnabled: boolean`
- `skillLearningEnabled: boolean`

### `GET /agent/threads/{threadId}/messages`

assistant 消息 `metadata` 兼容新增：

- `taskFrame`
- `knowledgeRefs`
- `facts`
- `answerGuard`

### `GET /agent/runs/{runId}/events`

兼容新增事件 `knowledge.retrieved`，包含命中数量和可公开的引用摘要，不包含内部 Prompt 或敏感上下文。

## Future Data Model

后续动态知识阶段新增：

- `agent_knowledge_source`
- `agent_knowledge_chunk`
- `agent_semantic_definition`
- `agent_run_knowledge_ref`

Skill Studio 阶段新增：

- `agent_skill_definition`
- `agent_skill_version`
- `agent_skill_test_case`
- `agent_skill_validation_run`

迁移必须以 `use zjyz;` 开头，兼容 MySQL 5.7；JSON 内容使用 `LONGTEXT` 并由应用校验，索引必须包含租户和权限范围字段。

## Acceptance Criteria

- 同一会话连续提出两个不同问题时，第二轮不复述或执行上一轮任务。
- 真正追问可使用有限的上一轮上下文。
- 需要业务数字的回答没有成功工具证据时，返回明确的不可核验提示，不编造数字。
- 未批准、冲突或被排除的文档不会进入知识上下文。
- 每轮 assistant 消息可查看 Task Frame、知识引用、事实证据和回答守卫结果。
- 工具目录最多暴露 6 个本轮相关且适用当前范围/权限的工具。
- `cid`、`uid` 与项目范围继续由服务端校验，跨企业、跨项目、跨用户访问被拒绝。
- 第一阶段前后端均通过构建，既有 `/agent` 接口路径保持一致。
- 后续目标：至少 80% 的只读查询表达变体可通过知识与通用工具完成，而无需新增专用 Skill。

## Implementation Phases

1. 正确性基础：Task Frame、可信内置知识包、有限上下文、工具目录分层、知识/事实审计与前端展示。
2. 通用业务查询：项目、合同、单据、结算周期、应收、库存和材料统一数据工具与表格渲染器。
3. 动态知识库：租户/项目知识、版本发布、Embedding 与 Qdrant、引用持久化。
4. Hermes Skill Learning：从成功 Run 生成受限 DSL Skill，经验证和人工审批后发布。

## Verification Plan

- 后端：Task Frame、知识检索、工具可见性和回答守卫单元测试；`mvn -DskipTests compile`。
- 前端：`npm run build`，确认旧消息无新增 metadata 时仍正常显示。
- API：运行 API prefix scanner，确认前后端继续使用 `/agent`，无需新增 nginx location。
- 人工场景：依次询问“今年新增项目”和“8 月哪些项目应该对账”，验证第二轮 Task Frame、工具选择和回答均不沿用第一轮。
