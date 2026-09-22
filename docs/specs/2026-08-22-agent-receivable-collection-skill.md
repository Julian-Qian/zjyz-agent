# Spec: 企业级应收催缴清单 Skill

- Status: Approved
- Approved at: 2026-08-22
- Implementation: Completed locally on 2026-08-22; not deployed
- Skill: `ReceivableCollectionListSkill`
- Tool code: `finance.receivable_collection_list`
- Risk: `READ`
- Workspace scope: `TENANT`

## Background

现有 Agent Workspace 固定绑定 `cid + projectId`，不能回答“截至年底，企业内哪些项目没有按期付款、需要追缴”一类跨项目经营问题。系统已有正式应收账期、到期日、应收本金、收款分配和未收余额，因此催缴清单必须由后端确定性计算，GLM 只负责理解用户筛选条件和总结结果。

## Goals

- 新增企业级 `TENANT` Workspace，与现有 `PROJECT` Workspace 并存。
- 按指定查询日跨当前 `cid` 聚合所有租出项目的到期未收账期。
- 同时区分本年度到期未清与历史遗留未清。
- 输出项目级追缴金额、最早到期日、最长逾期天数、账期数、最近付款日和负责人。
- 按配置化阈值生成 P1/P2/P3 催缴优先级。
- 在聊天中返回结构化催缴卡片，并将完整明细保存为 Artifact。
- 支持从拥有权限的 Artifact 下载 Excel。
- 所有结果带查询日、筛选条件、计算口径、证据和一致性校验结果。

## Non-goals

- 不自动发送短信、邮件或微信。
- 不自动生成正式催款函或滞纳金单据。
- 不修改对账、结算、收款或业务单据。
- 不处理租入项目供应商应付。
- 不引入合同付款宽限期；第一期沿用现有应收账期 `due_date`。
- 不实现定时推送和客户信用评分。

## Assumptions

- “今年需要追缴”默认返回本年度到期未清和历史遗留未清，并分组展示。
- 第一期 `due_date` 等于正式财务对账周期结束日。
- 企业财务 Skill 使用独立 Feature 权限 `AGENT_FINANCE_COLLECTION`，不能只依赖普通 Agent 白名单。
- 默认 P1：逾期不少于 90 天或待追缴不少于 100000 元；P2：逾期不少于 30 天或待追缴不少于 50000 元；阈值后端配置化。
- 查询日不得晚于服务器当前业务日期。

## Business rules

待追缴账期：

```text
cid = current cid
AND due_date <= asOfDate
AND status != VOID
AND outstandingAsOfDate > 0
```

历史查询不能使用今天的 `outstanding_amount` 冒充历史余额：

```text
allocatedAsOfDate = SUM(active allocation amount WHERE effective_payment_date <= asOfDate)
outstandingAsOfDate = principal_amount - allocatedAsOfDate
```

项目汇总：

```text
principalAmount = SUM(principal_amount)
receivedAsOfDate = SUM(allocatedAsOfDate)
outstandingAmount = SUM(outstandingAsOfDate)
earliestDueDate = MIN(due_date)
maxOverdueDays = asOfDate - earliestDueDate
periodCount = COUNT(outstandingAsOfDate > 0)
```

返回前必须满足：项目汇总等于账期明细汇总、本金减已收等于未收、未收不得为负；失败时返回 `FIN409`，禁止 GLM 自行修正。

## Workspace and permission scope

- `PROJECT` Workspace 保持现有行为。
- `TENANT` Workspace 绑定 `cid`，不绑定具体项目。
- 企业空间第一期只暴露 `finance.receivable_collection_list`。
- 项目空间不能调用企业财务 Tool，企业空间不能调用项目级 Tool。
- 企业空间要求 `AGENT_WORKSPACE` 与 `AGENT_FINANCE_COLLECTION` 均开通。
- `cid/uid/workspaceId/threadId/runId` 均由服务端注入，模型不能覆盖。

## Tool contract

输入：

```json
{
  "asOfDate": "2026-12-31",
  "scope": "ALL_OVERDUE",
  "year": 2026,
  "minOutstandingAmount": 0,
  "minOverdueDays": 1,
  "projectKeyword": null,
  "customerKeyword": null,
  "managerName": null,
  "priorityLevels": ["P1", "P2", "P3"],
  "sortBy": "PRIORITY",
  "limit": 1000,
  "includeClosedLate": false
}
```

输出卡片类型：`receivable-collection-list`，包含 `summary/items/periods/filters/warnings/evidence`。聊天卡片最多展示前 20 个项目，完整项目与账期结果写入 Artifact。

## Data model

使用下一可用 migration，必须以 `use zjyz;` 开始且兼容 MySQL 8.0 以下版本。

`agent_workspace`：

- 新增 `scope_type`，默认 `PROJECT`。
- 新增 `scope_key`，现有记录回填 `project_id`；企业空间使用固定值 `TENANT`。
- `project_id` 改为可空。
- 唯一键调整为 `(cid, scope_type, scope_key)`。

`agent_artifact`：

- 新增 `workspace_id`。
- `project_id` 改为可空。
- 新增 `(cid, workspace_id, created_at)` 索引。

不修改 `settlement_receivable_period`、`customer_payment`、`customer_payment_allocation`、`settlement_document` 与 `contract`。

## API contract

保持 `/agent` 顶级前缀。

- `GET /agent/capabilities`：增加 `scopeModes` 和 `tenantFinanceEnabled`。
- `POST /agent/workspaces`：兼容原 `{projectId}`；企业空间使用 `{scopeType:"TENANT"}`。
- `GET /agent/artifacts/{artifactId}/download`：校验 `cid + ownerUid + thread ownership` 后输出 `.xlsx`。

错误码：`AGT400/403/404`，以及 `FIN400`（未来日期）、`FIN409`（一致性失败）、`FIN500`（生成失败）。

## Frontend behavior

- Agent 左栏增加“企业经营 / 项目空间”模式切换。
- 企业模式不显示项目选择器，展示企业应收催缴快捷问题。
- 催缴卡片展示项目数、待追缴总额、P1 数、最长逾期天数、Top 20 项目、年度分组和筛选口径。
- 支持查看完整 Artifact 和下载 Excel。
- 明确覆盖 loading、empty、permission denied、failed 和 download failed 状态。

## Backend scope

- 新增应收催缴 Service/Mapper/DTO/Skill Handler 和 Excel 生成器。
- Workspace/Artifact 模型、请求、Mapper、Service、Controller 支持 `TENANT`。
- Tool Registry 按 Workspace scope 输出不同工具定义，并接收/校验 Tool arguments。
- Run Executor 将模型 Tool arguments 传入 Registry。
- 降级分类器识别欠款、逾期付款、催缴、追缴、应收等关键词。
- 聚合查询不得逐项目 N+1。

## Acceptance criteria

- 跨租户、无权限和错误 Workspace scope 全部拒绝。
- 历史查询按付款有效日期重算；查询日后付款不影响历史欠款。
- 查询日前已结清账期不进入追缴清单。
- 默认同时展示本年度未清和历史遗留未清。
- 汇总金额与账期明细完全一致。
- 无欠款返回明确空状态；未来查询日期被拒绝。
- 对话卡片显示前 20 项，Excel 包含完整结果和口径说明。
- Artifact 下载重新验证所有权。
- 原项目 Workspace、Thread、项目经营 Skill 和 SSE 无回归。
- 后端编译、目标测试、前端构建和 API prefix 检查通过。

## Implementation order

1. Workspace/Artifact migration。
2. 企业 Workspace scope、Feature 权限和持久化兼容。
3. 应收催缴聚合和一致性校验。
4. Skill Tool schema、模型参数传递和降级识别。
5. Artifact 与 Excel 下载。
6. 前端企业模式、催缴卡片和导出。
7. 后端测试、前端构建、API/权限/跨租户验证。

## Rollback

- 关闭 `AGENT_FINANCE_COLLECTION` 即可隐藏并禁用企业财务能力。
- 新增字段保持向后兼容，不删除现有项目 Workspace。
- Skill 不注册时原项目 Agent 继续工作。
- 回滚应用版本时不删除应收、收款和结算业务数据。
