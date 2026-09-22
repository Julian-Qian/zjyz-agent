# Spec: 小云 Agent 准确率优化（路由、守卫、范围与能力覆盖）

- 状态：待评审
- 确认日期：2026-09-02
- 前端宿主：智建云租 React 应用（未包含）
- 后端宿主：智建云租 Spring Boot 应用（未包含）
- 依据：`docs/xiaoyun-agent-audit/final-capability-gap-report.md`（262 用例全量审计）、
  `docs/xiaoyun-agent-audit/dynamic-evaluation-summary.md`、
  `docs/specs/2026-08-28-rental-operations-agent-v2.md`

## Background

2026-08-26 全量审计（262 条正式用例、14 类）给出的基线：

| 指标 | 基线值 |
| --- | ---: |
| 回答行为通过率 | 151 / 255 = 59.2% |
| 当前能力执行通过率 | 70 / 255 = 27.5% |
| 工具路由错误 | 29 条 |
| 范围错误 | 35 条 |
| 语义替代错误 | 11 条 |
| 证据不足仍输出结论 | 44 条 |
| 执行失败（无答案） | 7 条 |

用户可感知的"笨"可归为两类，均已定位到系统性根因，而非模型能力问题：

1. **"有些问题回答不上来"** —— 静态分析 128 条 `NONE` 用例中，109 条属于
   "系统有数据但 Agent 未接入工具"，仅 19 条真缺数据。是能力覆盖缺口。
2. **"有些 Skill 没触发到"** —— 三个叠加原因：
   - Planner 依赖自然语言相似度选工具，没有"指标 → 最小必要工具"的确定绑定
     （应收问题被路由到待对账、项目活动、合同甚至帮助工具）；
   - Tool Registry 点号（`finance.receivable_collection_list`）与下划线
     （`finance_receivable_collection_list`）双命名并存，Guard 对不上规范名，
     出现"工具已执行但 Guard 判缺证据"的误拦（审计确认 9 条）；
   - V1.5 的 `AgentToolCatalog.modelDefinitions()` 每轮只按 relevance 打分暴露
     Top 6 工具，若打分裁掉正确工具，模型根本"看不见"它。

审计明确的修复原则：**不要用堆提示词替代确定性聚合、权限范围和业务语义层**。
本 Spec 将审计的 P0/P1 建议落成可执行的设计与任务清单。

## Goals

- 必需工具召回率 ≥ 98%，错误业务领域替代率为 0（消灭"Skill 没触发"）。
- 范围外项目数据出现率为 0（含卡片与自然语言答案回扫）。
- "工具已成功执行却被 Guard 判缺证据"的比例为 0。
- 无能力用例伪精确数值率为 0；`UNSUPPORTED` 走结构化能力边界卡，不混排无关业务卡。
- 把 109 条"有数据未接入"缺口按老板高频优先级补齐（P1 六个聚合工具）。
- 262 用例回归集自动化，任何路由/Guard/Scope 改动可一键回归。

## Non-Goals

- 不引入任何业务写入、外部发送或审批执行（维持 READ/COMPUTE 边界）。
- 不做成本/利润/现金流/预测类能力（属审计 P2，先补数据模型再开放）。
- 不以更换主模型作为首要手段（可在 E3 中做 Interpreter 对比实验，但不阻塞主线）。
- 不重构 V1（V1 仅保底，投入全部进 V1.5 修复与 V2 灰度）。

## 根因分层与对应工作流

| 层次 | 根因 | 对应工作流 |
| --- | --- | --- |
| Tool Registry | 点号/下划线双命名，无统一 canonical code | WS-A |
| Planner | 缺"指标 → 最小必要工具"确定绑定；工具裁剪可能裁掉正确工具 | WS-B |
| Scope | 范围快照未成为工具查询与出栈的不可变约束 | WS-C |
| Guard | 只验证"调过工具"，不验证指标+范围+时间+单位+完整性 | WS-C |
| API/能力 | 企业聚合、单据审核、实体排行等未接入 | WS-D |
| 可靠性/评测 | 7 条执行失败；无自动回归；V2 灰度未推进 | WS-E |

---

## WS-A 工具规范名统一（先修"冤枉的笨"）

### 设计

- `AgentToolCodes` 作为唯一 canonical code 权威（点号形式，如
  `finance.receivable_collection_list`）；模型侧函数名（下划线形式）通过显式
  alias 映射表归一，禁止散落的字符串字面量。
- `AgentRuntimeToolRegistry`、Planner 工具定义、Evidence 记录、
  `AgentAnswerGuard` / V2 `hasCompleteBusinessEvidence()` 四处统一经
  `AgentToolCodes.canonical(String)` 归一后再比较。
- 新增一致性单测：遍历 Registry 注册的全部工具，断言"模型函数名 → canonical
  code → Guard 期望名"三向可互相解析；任何新工具接入不满足即编译期/测试期失败。

### 验收

- 审计中 9 条"工具已执行但 Guard 判缺证据"用例全部通过。
- 全仓 grep 无绕过 alias 映射的裸工具名比较。

## WS-B Planner 最小必要工具绑定

### 设计

- 新增版本化配置 `agent/routing/intent-tool-binding-v1.json`（后端 resources，
  带 `bindingVersion`）：`intent/metric → requiredToolCodes`，随 Evidence 落盘。
- 首批硬绑定（来自审计 P0-2）：
  - 应收 / 回款 / 催缴 → `finance.receivable_collection_list`
  - 期间租出 / 归还 / 赔偿数量 → `material.transaction_aggregate`
  - 未对账 / 对账缺口 → `project.reconciliation_due`
- 六类业务语义隔离为显式否定规则（Planner 提示词 + Guard 双侧执行）：
  付款逾期 ≠ 未对账 ≠ 合同到期 ≠ 业务停滞；期间流水 ≠ 当前占用；
  物料对账无记录不能推断"无赔偿"；历史活动不能推断"今日任务"。
- 工具裁剪修正：`AgentToolCatalog.modelDefinitions()` 中命中绑定表的
  `requiredToolCodes` 无条件入选（不受 Top 6 名额挤占）；每次 Run 落盘
  `exposedTools` 与 `requiredToolsExposed: boolean`，作为可观测指标。

### 验收

- 所有 `minimumRequiredTools` 非空的审计用例，必需工具召回率 ≥ 98%。
- `XY-01-011`、`XY-03-001`～`017`、`XY-06-001`～`013` 关键集全部通过。
- 错误领域工具替代率为 0。

## WS-C 范围硬约束与 Guard 覆盖校验

### 设计（范围）

- Run 创建时持久化不可变 `tenantId + projectIds + scopeMode + scopeVersion`；
  工具只读当前 Run 的 `ToolExecutionContext`，禁止读取旧会话或上一次选择。
- 每个工具返回实体执行 `result.projectId ⊆ authorizedProjectIds` 硬校验，
  失败则该结果不进 Evidence、卡片与答案均不渲染，并记 `SCOPE_VIOLATION` 事件。
- 企业级工具（库存概况、财务 KPI）在定义中显式声明 `scopeBehavior:
  IGNORES_SELECTION | RESPECTS_SELECTION`，禁止隐式扩大或缩小范围。
- 保留并复用现有 `AgentProjectScopeSnapshot.validateExecution()` 的答案文本
  回扫，把审计发现的公共链路缺口（`ScopeSnapshot → ToolExecutionContext`）
  补为单一注入点。

### 设计（Guard）

- `AgentCapabilityDecision` 返回 `SUPPORTED / PARTIAL / UNSUPPORTED` +
  必需指标、工具、范围与限制说明。
- 终局 Guard 从"调用过工具"升级为六要素覆盖校验：
  **指标 + 时间范围 + 对象 + 项目范围 + 单位 + 结果完整性（totalCount /
  displayedCount / truncated）**；缺任一要素按 PARTIAL 处理并在答案中显式声明。
- `UNSUPPORTED` 时输出结构化"能力边界卡"（新增 `card.type =
  capability-boundary`）：已确认什么、缺什么数据/工具、为什么不能下结论、
  可执行的替代动作；**禁止混排弱相关业务卡片**。
- 异质单位红线：跨计数单位（根/吨/片/只）不得合计；沿用
  `materialRanking.js` 按单位分组的前端约束，后端聚合工具同样强制。

### 验收

- 审计 35 条 `SCOPE_ERROR` 用例回归 100% 通过；随机 100 次并发切换项目，
  越界率为 0。
- 44 条"证据不足仍输出结论"用例：伪精确数值率降为 0（转为诚实拒答或边界卡）。
- Guard 误放行率 < 1%，误拦截率为 0。

## WS-D 能力覆盖补齐（老板高频经营工具）

全部为后端确定性聚合 + 前端新卡片，按审计 P1 顺序交付；每个工具返回
`metricVersion`、`totalCount / displayedCount / truncated` 与口径说明。

| 序号 | 工具 | 说明 | 前置依赖 |
| --- | --- | --- | --- |
| D1 | `inventory.operations_summary` + `inventory.ledger_trace` | 复用 Inventory Workbench：企业库存 KPI、负库存全集、长期无流水、短缺/积压、盘点与台账差异 | 无 |
| D2 | `document.audit_list` | 草稿、未复核、字段/附件缺失、修改后重审、数量越界、日期断档，规则在后端统一实现并返回证据字段 | 无 |
| D3 | `business.entity_analytics` | 项目/客户/负责人/供应商/材料的业务量、回款、欠款、赔偿、占用排行 | **先建稳定主体 ID 与别名表**（客户/供应商按名称聚合存在同名、别名、空值问题） |
| D4 | `contract.commercial_analytics` | 商业规则、字段完整性、合同价与执行价偏差、同规格同单位价格比较 | 无 |
| D5 | `material.lifecycle_analytics` | 租龄、周转、赔偿率、多还、长期只租不还；保留负差额原值并定义 FIFO/快照口径 | 无 |
| D6 | `risk.owner_action_center` 完整性补强 | Top N 返回全集计数与截断披露；分值贡献可复算（沿用前端 `scoreReproducible` 校验） | 无 |

### 验收

- 对应 P1 用例集合动态通过率 ≥ 90%。
- 所有 Top N 带 `totalCount / displayedCount / truncated`；
  跨域风险总计与后端直查可 100% 复算；异质单位合计率为 0。

## WS-E 回归闭环、可靠性与 V2 灰度

### 设计

- **回归集自动化**：以 `docs/xiaoyun-agent-audit/test-cases.json`（262 条）+
  `evaluation-rubric.md` 为基线，扩展 `qa/agent-v2-local-smoke.mjs` 为可参数化
  回归 runner；规则化草评自动跑，`SCOPE_ERROR`/`FABRICATED`/财务类低分题保留
  人工抽查。P0 关键子集（范围 + 应收路由 + 语义隔离，约 60 条）作为发布门禁。
- **可追溯性**：每次 Run 落盘 runId、model、canonical tool code、参数摘要、
  Evidence、GuardDecision、scope snapshot；生产 UI 的卡片口径区展示 runId
  以便对账问题回溯。
- **可靠性收尾**：复测审计 7 条执行失败（6 条 `FAILED_UI_RESPONSE_TIMEOUT` +
  1 条 `TIMEOUT`）；确保 `PLANNING/RUNNING` 有心跳与服务端终止态，UI 终态
  一致率 100%（前端 120s 超时 + SSE/轮询闩锁已具备，重点在服务端心跳补齐）。
- **V2 灰度推进**：WS-A/B/C 完成后，用回归集对 V1.5 与 V2（Capability
  Manifest + 结构化理解 + 混合召回）做同集对比；V2 路由指标达标
  （必需工具召回率 ≥ V1.5 且范围违规为 0）即按 cid 白名单逐步放量。
  绑定表（WS-B）是过渡期保险栓，V2 全量后降级为校验层而非路由层。
- **对比实验（可选）**：Interpreter 单点换更强模型跑同一回归集，仅作数据
  参考，不阻塞主线。

### 验收

- 连续 500 次只读任务有答案终态成功率 ≥ 99.5%；无永久 `PLANNING/RUNNING`。
- 基础查询 P50 < 10s、P95 < 30s；跨域老板总览 P95 < 45s。
- 100% 回答可关联 runId、model、tool code、参数、证据时间与 Guard 结果。

---

## 任务清单

估算为后端/前端人日；顺序即建议交付顺序，同一 Phase 内可并行。

### Phase A（第 1 周）：规范名与可观测 —— 修"冤枉的笨"

- [x] A1 后端 1d：`AgentToolCodes` 收敛为唯一权威 + alias 映射表，
      Registry/Planner/Evidence/Guard 四处统一归一比较
      ——【2026-09-02 完成】审计后 Phase 2 已建 `canonicalize` 与旧别名映射；
      本次补齐最后一处未归一比较：V2 `AgentV2RunExecutor.allowedToolCodes()`
      的白名单集合改为 canonical 归一后比较。
- [x] A2 后端 0.5d：三向解析一致性单测（模型函数名 ↔ canonical ↔ Guard 期望名）
      ——【2026-09-02 完成】新增 `AgentToolCodeConsistencyTest`（4 个用例）：
      全部 15 个工具三向互解析、`AgentToolCodes` 常量与注册表一一对应、
      审计发现的 4 个旧点号别名归一、大小写/空白容错。
- [x] A3 后端 1d：每 Run 落盘 `exposedTools` / `requiredToolsExposed`，
      作为路由可观测指标进 `agent_run_event`
      ——【2026-09-02 完成】V1.5 `AgentRunExecutor` 在规划前发布 `tool.exposed`
      事件（exposedTools + requiredTools + requiredToolsExposed）；
      V2 `executePlan` 发布 exposedTools + allowedTools。前端跳过该事件不进视图。
- [x] A4 后端 1d：服务端心跳与终止态补齐，复测 7 条执行失败用例
      ——【2026-09-02 完成】DB 心跳/watchdog/事务化收尾在 2026-08-26 Phase 2
      整改中已落地；本次补齐其残余项"SSE 层 heartbeat"：
      `AgentRunEventService` 每 15s（`agent.runtime.sse.heartbeatMs`）向在线
      订阅者推送不落库、无序号的 `heartbeat` 事件并清理断开的连接；
      前端 SSE 消费端显式忽略。7 条执行失败用例复测归入 A5 环境验证。
- [ ] A5 验证 0.5d：审计 9 条"已执行被误拦"用例 + 7 条执行失败用例回归通过
      ——待部署测试环境后用 `qa/agent-v2-local-smoke.mjs` 与审计用例复测。

Phase A 验证记录（2026-09-02）：后端 `mvn compile` 通过；定向测试 52/52 通过
（含新增 5 个）；前端 agent 相关 8 suites / 66 tests 全部通过。

### Phase B（第 2 周）：路由绑定与语义隔离 —— 修"选错工具"

- [x] B1 后端 1.5d：`intent-tool-binding-v1.json` 版本化绑定表 + 加载与匹配逻辑
      ——【2026-09-02 完成】新增 `resources/agent/routing/intent-tool-binding-v1.json`
      （4 条下限规则 + 6 条语义红线，带 bindingVersion）与加载/匹配类
      `AgentIntentToolBindingTable`（规则支持 anyOf/allOfGroups/exclude/
      selectionModes，加载时校验 canonical code，配置错误启动即失败）。
      设计定位：下限保险栓，非排他路由——`AgentTaskFrameBuilder` 在全部守卫
      分支通过后于末端并集注入（帮助/企业知识/不支持指标的提前拒答不受影响）；
      `bindingVersion` 随任务帧与 `tool.exposed` 事件落盘。
- [x] B2 后端 1d：首批三条硬绑定（应收/期间流水/未对账）+ 六类语义否定规则
      落入 Planner 提示词与 Guard 双侧
      ——【2026-09-02 完成】硬绑定与语义排除编码在绑定表规则中（催缴→应收
      清单限 ALL/MULTI 范围；期间流水排除金额/占用/财务词；未对账排除逾期
      付款词）；六类语义红线以绑定表为单一事实源注入 V1.5 与 V2 两侧系统
      提示词；Guard 侧 V1.5 经 minimumRequiredTools 自动生效，V2 新增
      下限校验：命中规则的工具无执行证据即 BLOCKED 并记 warning。
- [x] B3 后端 0.5d：`modelDefinitions()` 裁剪修正 —— 绑定表命中的工具无条件入选
      ——【2026-09-02 完成】`AgentToolCatalog` 改为"必需工具先入选、
      非必需工具按相关性填充至上限 6"，必需工具不再依赖 +200 打分间接保证。
- [x] B4 验证 1d（单测部分）：新增 `AgentIntentToolBindingTableTest`（8 用例：
      三条硬绑定、范围/方向/帮助类排除、语义排除、canonical 校验、并集去重）
      与 `AgentToolCatalogExposureTest`（3 用例：打分劣势下必需工具仍入选、
      多必需工具全入选、UNSUPPORTED 仍不暴露）。agent 包全量 245/245 通过，
      既有 34 条任务帧测试与 9 条 Catalog 测试零扰动。
      `XY-03-*`/`XY-06-*`/`XY-01-011` 的 E2E 召回率复测待部署环境（并入 A5）。

Phase B 验证记录（2026-09-02）：后端 `mvn compile` 通过；
`com.zjyz.agent.**` 全量 245 tests / 0 failures。

### Phase C（第 2～3 周）：范围硬校验与 Guard 升级 —— 修"越界与误判"

- [x] C1 后端 1.5d：`ToolExecutionContext` 单一注入点 + 不可变范围快照，
      工具禁止读取旧会话选择
      ——【2026-09-02 核实为已完成】Phase 2 整改已落地：`AgentProjectScopeSnapshot`
      不可变快照 + `workspace = scopeSnapshot.toWorkspace()` 单一注入点，
      执行期只读冻结副本；本阶段核对无残余缺口。
- [x] C2 后端 1d：出栈硬校验 + 失败记 `SCOPE_VIOLATION` 并阻断渲染
      ——【2026-09-02 完成】递归 projectId ⊆ authorized 校验（卡片/证据/答案
      文本回扫/产物）已在 Phase 2 落地并抛 `AGT_SCOPE_VIOLATION` 阻断；
      本次补齐审计缺口：V1.5 与 V2 工具失败路径新增 `scope.violation`
      事件落盘 `agent_run_event`，使越界率成为可统计指标。
- [x] C3 后端 0.5d：企业级工具显式声明 `scopeBehavior`
      ——【2026-09-02 完成】`AgentToolDescriptor` 新增 scopeBehavior 契约
      （RESPECTS_SELECTION 默认 / IGNORES_SELECTION / NOT_APPLICABLE）；
      库存概况声明 IGNORES_SELECTION 并在模型可见描述中强制披露
      "按企业全量口径统计，忽略项目多选"；帮助与材料预估声明 NOT_APPLICABLE。
- [x] C4 后端 2d：Guard 覆盖校验升级 + 三态能力判定
      ——【2026-09-02 完成】`AgentAnswerGuard` 新增证据感知重载：在"必需工具
      是否执行"之上，校验必需工具证据的时间范围/项目范围/记录数要素
      （指标要素由 canonical toolCode 承载，对象与单位要素由业务卡口径字段
      承载）；要素缺失不拦截答案但判 PARTIAL 并在答案中显式声明"口径提示"。
      Decision 新增 capability（SUPPORTED/PARTIAL/UNSUPPORTED）与
      coverageGaps，随 answerGuard 审计元数据落盘。
- [x] C5 后端 0.5d + 前端 1d：`capability-boundary` 能力边界卡
      ——【2026-09-02 完成】后端 Guard 未通过时输出结构化边界卡（status/
      missingTools 带展示名/nextStep/scopeNote），取代空卡片区；弱相关业务卡
      在 Guard 失败时本已被阻断。前端新增 `capabilityBoundary.js` 规整器
      （含 3 个单测）+ `ResultCards` 分支 + 样式。
- [ ] C6 验证 1d：35 条 `SCOPE_ERROR` + 44 条 `FABRICATED` 用例全量回归；
      100 次并发切换项目越界率为 0 ——待部署环境（并入 A5 复测批次）。

Phase C 验证记录（2026-09-02）：后端 `com.zjyz.agent.**` 251 tests / 0 failures
（新增 Guard 覆盖 5 例 + scopeBehavior 1 例）；前端 9 suites / 69 tests 通过
（新增 capabilityBoundary 3 例）；前端生产构建通过。

### Phase D（第 3～6 周）：能力覆盖补齐 —— 修"答不上来"

- [x] D1 后端 2d + 前端 1d：`inventory.operations_summary` / `ledger_trace`
      + 库存运营卡
      ——【2026-09-03 完成】两个新 Skill 复用库存工作台确定性口径：
      运营汇总输出四类异常（负库存/在租为负/租入未退为负/长期无流水）的
      全集计数与明细（含处理建议）；台账追溯按材料输出流水与变动前后余额，
      材料不唯一时返回澄清而不猜测。均声明 IGNORES_SELECTION 企业全量口径，
      不做跨单位合计。前端新增 `inventoryCards.js` 规整器（4 单测）+ 两张卡。
- [x] D2 后端 2d + 前端 0.5d：`document.audit_list` + 单据审核卡
      ——【2026-09-03 完成】跨五类单据（租出/归还/赔偿/租入/退租）的审核
      清单；v1 规则覆盖未复核与业务日期缺失，附件/数量越界/日期断档留
      v2 并在口径中显式声明。按类型与问题双维计数 + 全集/截断披露。
      审计用例"今天有哪些待审核单据"由诚实拒答升级为已支持能力
      （相应更新任务帧测试预期）。前端 `documentAudit.js`（2 单测）+ 卡片。
- [ ] D3 后端 3d（含 1d 数据建模）+ 前端 1d：主体 ID/别名表 +
      `business.entity_analytics` + 实体排行卡
      ——【进行中】主体建模涉及新建表，迁移草稿已起草待评审：
      `migration/V131_create_business_entity_registry_DRAFT.sql`
      （business_entity + business_entity_alias，Top 主体先行、
      长尾 UNMATCHED 显式披露、别名合并不改写历史名称、5.7 兼容幂等）。
      评审通过后再实现排行 Skill 与卡片。
- [x] D4 后端 2d + 前端 0.5d：`contract.commercial_analytics` + 合同分析卡
      ——【2026-09-03 完成】v1 两类确定性分析：合同关键字段完整性
      （无合同/缺结束日期/缺结算周期/缺税率/开启超期递增却缺递增率）、
      同名称+规格+计数单位材料的跨项目日租金价差（≥2 项目且有差异，
      按价差比例排序）。合同价与单据执行价偏差留 v2 并声明。
- [x] D5 后端 2d + 前端 0.5d：`material.lifecycle_analytics` + 材料生命周期卡
      ——【2026-09-03 完成】租出方向三类分析：多还（保留负差额原值）、
      长期只租不还（有未归还且超过 minStagnantDays 无归还流水，默认 60 天，
      租龄按项目最近归还距今近似，FIFO 逐笔留 v2）、赔偿率排行
      （按名称+规格+计数单位分组）。租入方向暂不纳入并声明。
- [x] D6 后端 1d：老板行动中心 Top N 完整性（全集计数 + 截断披露）
      ——【2026-09-03 核实为已完成】Result 模型已带 totalCount/
      displayedCount/truncated/limit，答案文本披露"匹配 N 个项目、
      展示前 M 个"与排序依据边界，前端 riskCards 已有对应处理。
- [ ] D7 验证 1d：P1 关联用例通过率 ≥ 90% ——待部署环境（并入 A5 批次）。

Phase D 交付说明（2026-09-03）：新增 6 个只读工具（含 D1 两个），工具总数
15 → 21；全部遵循统一契约——canonical code 注册（A2 一致性单测强制）、
scopeBehavior 声明、metricVersion、totalCount/displayedCount/truncated、
证据带时间范围/项目范围/记录数（满足 C4 Guard 覆盖校验）、跨单位不合计；
任务帧新增关键词绑定与既有绑定互斥（生命周期优先于流水聚合与占用）。
验证：后端 `com.zjyz.agent.**` 265 tests / 0 failures（新增 10 例）；
前端 12 suites / 79 tests 通过（新增 4 个规整器共 10 例）；前端生产构建
通过且无新增告警。

### Phase E（贯穿）：回归闭环与 V2 灰度

- [ ] E1 QA 2d：262 用例回归 runner（扩展 `qa/agent-v2-local-smoke.mjs`，
      支持子集筛选与规则化草评输出）
- [ ] E2 QA 0.5d：P0 关键子集（约 60 条）接入发布门禁，出现范围越界/
      财务串账/伪造金额任一项即一票否决
- [ ] E3 后端 1d + QA 1d：V1.5 vs V2 同集对比评测，产出灰度放量建议
- [ ] E4 运维 0.5d：V2 白名单放量（`AGENT_V2_ALLOWED_CIDS` 分批扩大），
      每批跑回归子集
- [ ] E5（可选）QA 1d：Interpreter 换模对比实验

## 量化验收门槛（发布一票否决制，沿用审计 §7）

| 维度 | 门槛 |
| --- | --- |
| 权限/范围 | 跨企业、范围外项目、范围外卡片泄露均为 0 |
| 工具路由 | 必需工具召回率 ≥ 98%；错误领域替代率 0 |
| 诚实性 | 无能力用例伪精确数字率 0；弱相关证据误放行 < 1% |
| 单位/聚合 | 异质单位求和率 0；总计 = 分组之和；截断全部显式披露 |
| 可追溯 | 100% 回答可关联 runId、model、tool code、证据与 Guard 结果 |
| 可靠性 | 500 次只读任务终态成功率 ≥ 99.5%；UI/后端终态一致率 100% |
| 性能 | 基础查询 P50 < 10s、P95 < 30s；跨域总览 P95 < 45s |
| 回归 | P0 用例 100%；P1 关联用例 ≥ 90%；7 条执行失败复测全部可评分 |

一票否决项：跨企业/项目越界、财务方向串账、伪造金额、把未对账当付款逾期、
把历史活动当今日任务 —— 任一出现即阻断上线。

## 风险与缓解

- **绑定表变成第二套关键词系统**：绑定表只做"必需工具下限"校验与召回保底，
  不做排他路由；V2 全量后降级为校验层。以 `bindingVersion` 落盘保证可追溯。
- **Guard 收紧导致拒答率上升**：C4 与 C5 必须同批上线 —— 拒答必须换来结构化
  边界卡的体验补偿；灰度期监控"诚实拒答率"与用户重试率。
- **D3 主体建模周期风险**：别名表先覆盖 Top 客户/供应商（按业务量），
  长尾允许 `UNMATCHED` 显式披露，不阻塞排行工具上线。
- **回归集草评偏差**：规则化草评仅做趋势监控；范围、财务、语义、虚构四类
  维持人工抽查，发布数字以人工复核口径为准。
- **数据库兼容**：D3 主体表等新增迁移遵循仓库约定 —— `use zjyz;` 开头、
  兼容 MySQL 8.0 以下、幂等可重放。
