# 小云已有业务能力接入

状态：用户已确认方案并授权实施（2026-09-12）。不包含生产发布。

## 目标

让 Agent 使用产品已有服务完成项目、合同、五类项目单据、库存、仓库/工地盘点、商城、预估、收付款和基础资料任务。系统是否具备能力由注册表判断，不能要求用户确认；没有项目不能阻断库存或商城。每项结论必须对应真实执行结果，不能把数量当金额、日志当回答、部分成功当全部完成。

## 实施顺序

- A：明确能力参数、作用范围、权限、完成标准，修复空项目范围；保留原始目标和多步骤要求。
- B：按真实服务接入只读查询，统一分页、对象校验和业务结果展示。
- C：白名单页面导航、生成文件、材料预估与库存/商城衔接、可审阅草稿。
- D：在具体内容确认后执行支持的写操作，校验版本、权限、幂等和取消状态。

## 范围和约束

项目选择、企业库存/基础资料、公开商城独立。项目详情必须核验租户及冻结项目范围；商城仅使用已发布信息和公开联系方式。GET 接口并不天然只读。联系站点日志不代表已联系或下单。财务权限不因工具适配而绕过。租出、归还、赔偿、租入、退租五类单据共同覆盖；图像识别当前只支持租出单。

所有适配调用已有服务，禁止模型指定任意 HTTP 地址、SQL、类名或方法。管理员授权、账户凭证、订阅支付不进入通用自动写入。未完成/失败的步骤必须单独显示。

## 验收

覆盖无项目查商城/库存、跨租户和项目越界、空结果、分页、参数缺失与恢复、五类单据、商城价格单位不可比较、非公开联系方式、多步骤部分失败、草稿取消、重复提交和版本变化。运行针对性后端测试、前端测试及构建；真实模型/生产验证未执行时明确记录。

## 实施记录

本文件随实现更新，完成项以代码和测试为准。


### 首批已实现

- 注册表新增 57 个明确的服务适配（含页面入口和文件导出），每个都声明参数、作用范围和必需输入；禁止通用 URL/SQL/方法调用。
- 无项目时仍开放库存、企业资料、预估和商城。项目查询同时校验冻结项目范围及当前租户归属。
- TaskSpec 分项要求保留具体条件，验证能力证据、条件匹配和不同查询结果；失败、预算耗尽及模型中断不会因已有一项成功结果而标记全部完成。旧任务没有分项要求时仍保留原有证据检查。
- 新服务适配通过 AgentComputeSandbox 执行，回滚旧查询中可能存在的初始化/日志写入；任务自身的消息、依据和下载产物照常保存。
- 商城增加可选精确规格、计数单位、报价单位、最低可供量和 PRICE_ASC 排序；价格排序在数据库全部匹配集合上执行，必须限定一致单位，未报价项不参与。原查询参数保持兼容。
- 业务结果卡使用中文字段、分页与范围说明，支持展开本页；公开联系信息遵循显示权限。原始 JSON 不直接展示。
- 白名单页面入口覆盖主要业务模块及项目内合同、材料、五类单据、物料/财务对账和付款页面；商城入口带入已验证的筛选条件。
- 五类项目单据与预估 Excel 复用原导出服务，保存为可下载任务产物；下载沿用已有鉴权接口。

### 尚未实施，不计为已完成

- 通用可编辑操作草稿、写入预览/确认接口及持久化动作凭证。
- 项目/合同/材料/人员修改、五类单据保存与复核/反复核、盘点确认、对账结算提交、付款登记/删除的 Agent 直接执行；其现有业务页面与原操作继续可用。
- PDF/打印、仓库及财务对账导出等其余文件适配，以及图纸流程的更多接入。
- 真实模型对全部自然语言任务的回归，以及生产浏览器联调和发布。

### 接入清单

| 能力代码 | 业务能力 | 数据范围 |
| --- | --- | --- |
| `market.search` | 商城找材料 | 公开商城 |
| `market.cities` | 商城可选城市 | 公开商城 |
| `market.detail` | 商城材料详情 | 公开商城 |
| `market.contact` | 站点公开联系方式 | 公开商城 |
| `inventory.materials` | 库存材料清单 | 企业库存 |
| `inventory.material_detail` | 材料库存明细 | 企业库存 |
| `inventory.material_ledger` | 材料库存流水 | 企业库存 |
| `inventory.material_trend` | 材料库存趋势 | 企业库存 |
| `inventory.workbench` | 库存工作台 | 企业库存 |
| `inventory.count_tasks` | 仓库盘点任务 | 企业库存 |
| `inventory.count_detail` | 仓库盘点详情 | 企业库存 |
| `inventory.site_distribution` | 企业工地材料分布 | 企业库存 |
| `project.detail` | 项目详情 | 选中项目 |
| `project.progress` | 项目材料租还进度 | 选中项目 |
| `project.site_materials` | 项目工地材料 | 选中项目 |
| `project.site_ledger` | 项目工地材料流水 | 选中项目 |
| `project.site_count_tasks` | 项目工地盘点任务 | 选中项目 |
| `contract.detail` | 项目合同详情 | 选中项目 |
| `contract.material_categories` | 合同材料分类 | 选中项目 |
| `contract.material_prices` | 合同材料价格 | 选中项目 |
| `contract.price_ladders` | 合同材料阶梯价 | 选中项目 |
| `document.list_records` | 项目单据列表 | 选中项目 |
| `document.detail` | 项目单据详情 | 选中项目 |
| `inventory.documents` | 仓库单据列表 | 企业库存 |
| `master.materials` | 企业材料库 | 企业资料 |
| `master.categories` | 材料分类 | 企业资料 |
| `master.units` | 材料计数单位 | 企业资料 |
| `master.pricing_units` | 材料计价单位 | 企业资料 |
| `master.personnel` | 人员资料 | 企业资料 |
| `master.transport` | 运输人员与车辆 | 企业资料 |
| `estimate.list` | 材料预估记录 | 企业资料 |
| `estimate.detail` | 材料预估详情 | 企业资料 |
| `estimate.match` | 预估材料库存匹配 | 企业资料 |
| `estimate.supported_scope` | 材料预估支持范围 | 企业资料 |
| `finance.customer_payments` | 客户登记收款 | 选中项目 |
| `finance.supplier_payments` | 供应商登记付款 | 选中项目 |
| `finance.settlement_periods` | 项目结算账期 | 选中项目 |
| `finance.settlement_records` | 项目财务对账单 | 选中项目 |
| `project.reports` | 项目经营报告记录 | 选中项目 |
| `master.billing_units` | 开票单位 | 企业资料 |
| `market.estimate_shortage` | 预估缺口商城推荐 | 公开商城 |
| `inventory.document_detail` | 仓库单据详情 | 企业库存 |
| `inventory.temporary_open` | 未退完的暂存单 | 企业库存 |
| `inventory.temporary_returnable` | 暂存可退材料 | 企业库存 |
| `project.site_count_detail` | 工地盘点详情 | 选中项目 |
| `document.review_history` | 单据复核记录 | 选中项目 |
| `project.report_detail` | 项目经营报告详情 | 选中项目 |
| `finance.customer_payment_detail` | 客户收款详情 | 选中项目 |
| `finance.supplier_payment_detail` | 供应商付款详情 | 选中项目 |
| `finance.settlement_detail` | 财务对账单详情 | 选中项目 |
| `project.reconciliation_records` | 物料对账单列表 | 选中项目 |
| `project.reconciliation_detail` | 物料对账单详情 | 选中项目 |
| `contract.misc_fees` | 合同材料杂费 | 选中项目 |
| `navigation.project_workflow` | 打开项目业务页面 | 选中项目 |
| `navigation.open` | 打开业务功能 | 企业资料 |
| `document.export` | 导出项目单据Excel | 选中项目 |
| `estimate.export` | 导出预估Excel | 企业资料 |

## 本批验证记录

- 后端 15 个相关测试类，共 93 项测试通过。包括空项目执行商城、第二步失败保持部分结果、要求/城市条件证据匹配、参数拒绝、租户/项目越界、财务开关、五类单据查询及 Excel 导出、商城 SQL 条件及价格排序、旧范围与金额守卫回归。
- 前端 5 个测试文件，共 56 项测试通过；包含结果字段展示、非公开联系方式、导航白名单、项目路径注入、任务状态、并发/恢复回归。
- `npm run build` 通过，保留仓库原有 lint 与 bundle 提示。未执行真实账号浏览器联调或真实模型评测。
- 后端在隔离副本完成测试，再校验原文件哈希批量写回关联仓库；21 个变更文件写回后与测试版本一致。
- 没有新增 API 顶级前缀或数据库表；商城复用 `/market`，下载复用 `/agent/artifacts/{artifactId}/download`。本地前缀扫描没有识别位于 agent 包内的 Controller，已人工核对现有映射；没有本地生产 nginx 配置，未验证生产配置。
- 未发布前端、未重启后端、未执行数据库脚本。
