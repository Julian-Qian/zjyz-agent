# 小云 Agent 二阶段（执行型）与三阶段（助手型：单项目副驾 + 公司经营参谋）落地规范

## 1. 背景与定位

在第一阶段（知识库体系、确定性账务护栏、跨单位禁混加、基础查询工具集）验收稳定后，本规范落地第二阶段与第三阶段共 **12 项核心能力**，严格遵循以下四大架构铁律：

1. **只读试算与人工确认闭环（HITL Gate）**：所有单据与财务试算类能力均在 `AgentComputeSandbox.executeWithoutCommit(...)` 只读沙箱中执行，显式标记 `draftStatus = DRAFT_PREVIEW_ONLY` 或 `TRIAL_CALCULATION_ONLY` 及 `hitlConfirmationRequired = true`，绝不静默篡改正式业务库。
2. **零新增 DDL 与 100% 真实数据源绑定**：全部能力复用现有 MySQL / MongoDB 实体与业务服务（`ProjectSettlementService`、`ReconciliationPreviewService`、`FinanceOverdueCalculationService`、`MaterialMigrationService`、`MaterialEstimateService`、`MarketQueryService`、`ProjectContractService`、`MaterialInventoryService` 等），禁止虚构表结构或估算违约概率。
3. **跨单位物理隔离（Material Metric Guard）**：在库存满足率、波士顿矩阵、转租止损、跨工地直调等全部计算中，严格按 `materialName + specification + unit` 独立核算，禁止跨单位相加或跨单位除算。
4. **30 秒结论先行 + 一键跳转业务现场**：每张业务卡片统一输出 `conclusion30s`（30 秒结论横幅）、`draftStatus`（HITL 安全徽章）、`actionHint`（行动建议）与 `navigationTarget`（前端页面直达参数）。

---

## 2. 十二项新增能力矩阵

### 2.1 第二阶段：执行型 Agent（HITL 草稿试算预览 + 数据治理体检）

| 能力编码 | 能力名称 | 范围模式 | 财务门控 | 核心数据源与能力职责 | 前端一键跳转目标 |
| --- | --- | --- | --- | --- | --- |
| `settlement.preview_draft` | 财务结算单草稿试算预览 | `PROJECT_SELECTION` | 是 | 调用 `ProjectSettlementService.calculateProjectSettlement` 生成周期租金、超期递增租金、赔偿、杂费、报停减免与协商让利的只读试算预览 | `PROJECT_WORKFLOW` (`financeCheck`) |
| `reconciliation.preview_draft` | 物料对账单草稿试算预览 | `PROJECT_SELECTION` | 否 | 调用 `ReconciliationPreviewService.preview` 校验期初结存、本期租出、本期归还、本期赔偿与期末结存及未复核阻断项 | `PROJECT_WORKFLOW` (`materialReconciliation`) |
| `finance.late_fee_trial` | 逾期账款滞纳金试算器 | `PROJECT_SELECTION` | 是 | 调用 `FinanceOverdueCalculationService.getOverdueView` 按账期试算期初/期末欠款本金、逾期天数与累计未结滞纳金 | `PROJECT_WORKFLOW` (`financeCheck`) |
| `governance.material_alias_audit` | 材料主数据与迁移治理体检 | `TENANT_MASTER` | 否 | 联合 `MaterialMigrationService`、`MaterialAliasMapper` 与 `MaterialMapper` 体检待映射旧系统材料、缺失双单位材料与同名同规格重复项 | `MATERIALS` |

### 2.2 第三阶段 A：助手型 Agent — 单项目副驾（Project Copilot）

| 能力编码 | 能力名称 | 范围模式 | 财务门控 | 核心数据源与能力职责 | 前端一键跳转目标 |
| --- | --- | --- | --- | --- | --- |
| `copilot.order_fulfillment_eval` | 接单综合评估与商城寻源闭环 | `TENANT_MASTER` | 否 | 联合 `MaterialEstimateService`、`MaterialInventoryService` 与 `MarketQueryService`，输出自有库存满足率、缺口明细及租赁商城低价补货站点推荐 | `ESTIMATE` / `MARKET` |
| `copilot.contract_execution_audit` | 合同配置与执行双层合规审查 | `PROJECT_SELECTION` | 否 | 交叉核对 `ProjectContractService` 与实际已发料/未复核单据，识别“无合同定价先发料”、“超合同到期日发料”、“零单价合同项”等履约风险 | `PROJECT_WORKFLOW` (`contract`) |
| `copilot.price_breakeven_sandbox` | 项目报价让利与免租期盈亏沙盘 | `PROJECT_SELECTION` | 否 | 基于当前项目在租数量与合同日租金，对比 98折+免3天、95折+免7天、90折+免10天三档方案的让利总额与盈亏平衡延长租期 | `PROJECT_WORKFLOW` (`contract`) |
| `copilot.settlement_dispute_attribution` | 结算金额变动五因子归因解释 | `PROJECT_SELECTION` | 是 | 对比项目最近两期正式结算单，将跨期总额差异精准拆解为基础租金、超期递增、损坏赔偿、装卸运杂费、报停与让利五大因子及材料级差额 | `PROJECT_WORKFLOW` (`financeCheck`) |

### 2.3 第三阶段 B：助手型 Agent — 公司经营参谋（Enterprise Advisor）

| 能力编码 | 能力名称 | 范围模式 | 财务门控 | 核心数据源与能力职责 | 前端一键跳转目标 |
| --- | --- | --- | --- | --- | --- |
| `advisor.material_bcg_matrix` | 材料资产波士顿四象限与死库存诊断 | `TENANT_INVENTORY` | 否 | 按同规格同单位出租率与周转天数划分明星扩产区、金牛稳守区、问题观察区、瘦狗死库存区，并联动商城上架状态给出清仓转租建议 | `INVENTORY` |
| `advisor.counterparty_credit_profile` | 客户与供应商 360 度信用风控画像 | `FROZEN_SELECTION` | 是 | 聚合跨项目正式结算本金、核销履约率、逾期未付本金、最长逾期天数与累计赔偿单频次，输出 A/B/C/D 四级信用评级与授信建议 | `PROJECT_RENT_OUT` |
| `advisor.sublease_arbitrage_radar` | 自有闲置与外租未退止损雷达 | `TENANT_INVENTORY` | 否 | 自动扫描“仓库有自有闲置库存、同时租入项目仍在向外部供应商付租”的同规格同单位材料，计算可立即退租替换数量与日省/月省租金 | `PROJECT_RENT_IN` |
| `advisor.cross_site_transfer_plan` | 跨工地就近调拨优化建议 | `FROZEN_SELECTION` | 否 | 扫描近 30 天有归还退场余料的工地与持续发料需求工地，生成同规格同单位跨工地直调配对方案，减少二次回库装卸损耗 | `SITE_DISTRIBUTION` |

---

## 3. 代码落点清单

- **后端代码（`backend-smart-construction-cloud-rent` & `Julian-Qian___zjyz-agent/backend`）**：
  - `src/main/java/com/zjyz/agent/workspace/tool/AgentBusinessCapabilities.java`
  - `src/main/java/com/zjyz/agent/workspace/tool/AgentToolCodes.java`
  - `src/main/java/com/zjyz/agent/workspace/tool/AgentToolCatalog.java`
  - `src/main/java/com/zjyz/agent/workspace/tool/AgentRuntimeToolRegistry.java`
  - `src/main/java/com/zjyz/agent/workspace/v2/service/AgentV2CapabilityManifestService.java`
  - `src/main/java/com/zjyz/agent/workspace/context/AgentTaskFrameBuilder.java`
  - `src/main/java/com/zjyz/agent/workspace/tool/AgentPhase2And3Executor.java`
  - `src/main/java/com/zjyz/agent/workspace/tool/AgentBusinessQueryService.java`
  - `src/test/java/com/zjyz/agent/workspace/context/AgentTaskFrameBuilderTest.java`
  - `src/test/java/com/zjyz/agent/workspace/tool/AgentBusinessQueryServiceTest.java`
- **前端代码（`smart-construction-cloud-rent` & `Julian-Qian___zjyz-agent/frontend`）**：
  - `src/components/SubPage/AgentBeta/agentBusinessResults.js`
  - `src/components/SubPage/AgentBeta/BusinessRecordsCard.jsx`
  - `src/components/SubPage/AgentBeta/agentTaskView.js`
  - `src/components/SubPage/AgentBeta/agentBusinessResults.test.js`
