package com.zjyz.agent.workspace.tool;

import com.zjyz.agent.orch.AgentIntentType;
import com.zjyz.agent.workspace.context.AgentTaskFrame;
import com.zjyz.agent.workspace.context.AgentTaskCoverage;
import com.zjyz.agent.workspace.finance.ReceivableCollectionSkill;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeContext;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AgentToolCatalog {
    private static final int MAX_RELEVANT_TOOLS = 6;
    private final Map<String, AgentToolDescriptor> tools = new LinkedHashMap<>();
    private final Map<String, AgentToolDescriptor> aliases = new LinkedHashMap<>();

    public AgentToolCatalog() {
        for (AgentBusinessCapabilities.Definition capability : AgentBusinessCapabilities.definitions()) {
            register(capability.modelName(), capability.code, capability.description, "READ",
                    AgentIntentType.BUSINESS_QUERY, "ANY", capability.scopeBehavior(), capability.parameters);
        }

        register("project_list", AgentToolCodes.PROJECT_LIST,
                "按当前项目选择范围查询项目清单，支持按我方负责人managerName、对方负责人partnerName分别筛选；适用于小何负责哪些项目，也可按录入日期、业务类型、状态筛选。负责人查询不要用混合keyword代替",
                "READ", AgentIntentType.PROJECT_LIST, "ANY", projectListParameters());
        register("project_reconciliation_due", AgentToolCodes.PROJECT_RECONCILIATION_DUE,
                "按当前项目范围查询目标月份或截止日期的财务未对账缺口；minUnreconciledDays只表示未对账天数",
                "READ", AgentIntentType.PROJECT_RECONCILIATION_DUE, "ANY", reconciliationDueParameters());
        register("project_contract_status", AgentToolCodes.PROJECT_CONTRACT_STATUS,
                "按当前项目范围查询合同起止日期和派生状态，可筛选已到期、有效、未开始或缺少合同的项目",
                "READ", AgentIntentType.PROJECT_CONTRACT_STATUS, "ANY", contractStatusParameters());
        register("project_material_occupancy", AgentToolCodes.PROJECT_MATERIAL_OCCUPANCY,
                "按当前项目范围核算租出减归还或租入减退租后的当前未归还材料，返回项目和材料占用明细",
                "READ", AgentIntentType.PROJECT_MATERIAL_OCCUPANCY, "ANY", materialOccupancyParameters());
        register("material_transaction_aggregate", AgentToolCodes.MATERIAL_TRANSACTION_AGGREGATE,
                "仅按业务日期统计租出、归还、赔偿、租入或退租材料的数量和单据次数；数量或次数的排行、前N、累计使用本工具。不得用于赚钱、收入、回款、利润、金额或成本分析；最多只是排序条件，不能据此选择本工具",
                "READ", AgentIntentType.PROJECT_RENT_MATERIALS, "ANY", materialTransactionAggregateParameters());
        register("project_activity", AgentToolCodes.PROJECT_ACTIVITY,
                "按当前项目范围查询租出、归还、租入、退租、物料对账和财务对账的最近业务活动，可筛选长期无活动项目",
                "READ", AgentIntentType.PROJECT_ACTIVITY, "ANY", projectActivityParameters());
        register("risk_owner_action_center", AgentToolCodes.RISK_OWNER_ACTION_CENTER,
                "按当前项目范围确定性组合老板行动风险；支持指定riskTypes的ANY/ALL项目交集、固定评分版本和TopN，截止日与评分公式不可由模型修改",
                "READ", AgentIntentType.RISK_OWNER_ACTION_CENTER, "ANY", ownerActionCenterParameters());
        alias("risk.owner.action.center", AgentToolCodes.RISK_OWNER_ACTION_CENTER);
        register("project_get_summary", AgentToolCodes.PROJECT_SUMMARY,
                "生成当前单个项目经营快照，分析租赁量、已对账收入、回款欠款、风险与建议",
                "READ", AgentIntentType.PROJECT_SUMMARY, "SINGLE", defaultParameters());
        register("inventory_get_summary", AgentToolCodes.INVENTORY_SUMMARY, "查询企业库存概况、低库存和趋势",
                "READ", AgentIntentType.INVENTORY_SUMMARY, "ANY",
                AgentToolDescriptor.SCOPE_IGNORES_SELECTION, defaultParameters());
        register("inventory_operations_summary", AgentToolCodes.INVENTORY_OPERATIONS_SUMMARY,
                "查询企业库存异常运营汇总：负库存、在租为负、租入未退为负、长期无流水的全集计数与明细，含每项异常的当前值和处理建议",
                "READ", AgentIntentType.INVENTORY_OPERATIONS, "ANY",
                AgentToolDescriptor.SCOPE_IGNORES_SELECTION, inventoryOperationsParameters());
        register("inventory_ledger_trace", AgentToolCodes.INVENTORY_LEDGER_TRACE,
                "按材料追溯库存台账流水与变动前后余额，用于核查库存差异来源；必须提供材料名称或规格关键词",
                "READ", AgentIntentType.INVENTORY_LEDGER_TRACE, "ANY",
                AgentToolDescriptor.SCOPE_IGNORES_SELECTION, inventoryLedgerParameters());
        register("document_search", AgentToolCodes.DOCUMENT_SEARCH, "在当前单个项目中检索租出、归还、赔偿、租入、退租、对账或结算单据",
                "READ", AgentIntentType.DOCUMENT_SEARCH, "SINGLE", defaultParameters());
        register("document_audit_list", AgentToolCodes.DOCUMENT_AUDIT_LIST,
                "按当前项目范围列出命中审核规则的业务单据：未复核、业务日期缺失；支持按单据类型、日期区间和关键词筛选",
                "READ", AgentIntentType.DOCUMENT_AUDIT, "ANY", documentAuditParameters());
        register("material_estimate", AgentToolCodes.MATERIAL_ESTIMATE, "根据用户提供的建筑参数计算材料需求和库存匹配结果",
                "COMPUTE", AgentIntentType.MATERIAL_ESTIMATE, "ANY",
                AgentToolDescriptor.SCOPE_NOT_APPLICABLE, defaultParameters());
        register("help_search", AgentToolCodes.HELP_SEARCH, "检索智建云租帮助中心和系统操作说明",
                "READ", AgentIntentType.HELP_KNOWLEDGE, "ANY",
                AgentToolDescriptor.SCOPE_NOT_APPLICABLE, defaultParameters());
        register("material_lifecycle_analytics", AgentToolCodes.MATERIAL_LIFECYCLE_ANALYTICS,
                "租出方向材料生命周期分析：多还（归还超过租出）、长期只租不还项目、按名称规格单位分组的赔偿率排行",
                "READ", AgentIntentType.MATERIAL_LIFECYCLE, "ANY", materialLifecycleParameters());
        register("contract_commercial_analytics", AgentToolCodes.CONTRACT_COMMERCIAL_ANALYTICS,
                "合同商业分析：合同关键字段完整性核查，以及同名称同规格同计数单位材料的跨项目日租金价差比较",
                "READ", AgentIntentType.CONTRACT_COMMERCIAL, "ANY", contractCommercialParameters());
        register("finance_receivable_collection_list", AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST,
                "按截止日期重算企业全部租出项目应收余额，输出逾期催缴优先级、账期明细和可下载清单",
                "READ", AgentIntentType.FINANCE_RECEIVABLE_COLLECTION, "ALL", financeParameters());
        alias("finance.receivable_collection.list", AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST);
        register("finance_enterprise_kpi", AgentToolCodes.FINANCE_ENTERPRISE_KPI,
                "按当前项目范围汇总正式结算应收应付本金、ACTIVE本金核销和登记实收实付；不提供利润、营业额、完整现金流、资产或税务结论",
                "READ", AgentIntentType.FINANCE_ENTERPRISE_KPI, "ANY", financeLedgerParameters());
        alias("finance.enterprise.kpi", AgentToolCodes.FINANCE_ENTERPRISE_KPI);
        register("finance_supplier_payable_summary", AgentToolCodes.FINANCE_SUPPLIER_PAYABLE_SUMMARY,
                "按当前租入项目范围汇总正式应付本金、供应商登记实付、本金核销、未付及系统账期到期金额",
                "READ", AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY, "ANY", financeLedgerParameters());
        alias("finance.supplier.payable.summary", AgentToolCodes.FINANCE_SUPPLIER_PAYABLE_SUMMARY);
    }

    public List<Map<String, Object>> modelDefinitions(AgentRuntimeRecords.Workspace workspace) {
        return definitions(available(workspace));
    }

    public List<Map<String, Object>> modelDefinitions(AgentRuntimeRecords.Workspace workspace,
                                                       AgentTaskFrame taskFrame,
                                                       AgentKnowledgeContext knowledgeContext) {
        List<AgentToolDescriptor> available = available(workspace);
        if (taskFrame == null) {
            return definitions(available);
        }
        if (taskFrame.getCoverage() == AgentTaskCoverage.UNSUPPORTED_BUSINESS
                || "ENTERPRISE_KNOWLEDGE".equals(taskFrame.getDomain())) {
            return new ArrayList<>();
        }
        available.sort(Comparator
                .comparingInt((AgentToolDescriptor descriptor) -> relevance(descriptor, taskFrame, knowledgeContext))
                .reversed()
                .thenComparing(AgentToolDescriptor::getModelName));
        // 必需工具无条件入选（P0-2）：裁剪只作用于非必需工具，
        // 防止相关性打分把 minimumRequiredTools 挤出模型可见范围。
        List<String> requiredCodes = taskFrame.getMinimumRequiredTools() == null
                ? new ArrayList<>() : taskFrame.getMinimumRequiredTools();
        List<AgentToolDescriptor> selected = new ArrayList<>();
        for (AgentToolDescriptor descriptor : available) {
            if (requiredCodes.contains(descriptor.getToolCode())) {
                selected.add(descriptor);
            }
        }
        for (AgentToolDescriptor descriptor : available) {
            if (selected.size() >= MAX_RELEVANT_TOOLS) {
                break;
            }
            if (!selected.contains(descriptor)) {
                selected.add(descriptor);
            }
        }
        return definitions(selected);
    }

    public AgentToolDescriptor find(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return null;
        }
        return aliases.get(normalizeAlias(modelName));
    }

    public String canonicalToolCode(String nameOrAlias) {
        AgentToolDescriptor descriptor = find(nameOrAlias);
        return descriptor == null ? AgentToolCodes.canonicalize(nameOrAlias) : descriptor.getToolCode();
    }

    public AgentToolDescriptor find(AgentIntentType intent, AgentRuntimeRecords.Workspace workspace) {
        for (AgentToolDescriptor descriptor : tools.values()) {
            if (descriptor.getIntent() == intent && supportsSelection(descriptor, workspace)) {
                return descriptor;
            }
        }
        return null;
    }

    public Collection<AgentToolDescriptor> descriptors() {
        return tools.values();
    }

    public boolean supportsSelection(AgentToolDescriptor descriptor, AgentRuntimeRecords.Workspace workspace) {
        if (descriptor != null && workspace != null
                && AgentToolDescriptor.SCOPE_RESPECTS_SELECTION.equals(descriptor.getScopeBehavior())
                && "EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())
                && (workspace.getProjectIds() == null || workspace.getProjectIds().isEmpty())
                && !StringUtils.hasText(workspace.getProjectId())) return false;

        if (descriptor == null || workspace == null || "ANY".equals(descriptor.getSelectionSupport())) {
            return true;
        }
        return descriptor.getSelectionSupport().equals(selectionKind(workspace));
    }

    String selectionKind(AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null) {
            return "SINGLE";
        }
        if ("ALL".equalsIgnoreCase(workspace.getSelectionMode())) {
            return "ALL";
        }
        if (workspace.getProjectIds() != null && workspace.getProjectIds().size() == 1
                && StringUtils.hasText(workspace.getProjectId())) {
            return "SINGLE";
        }
        if (workspace.getProjectIds() != null && workspace.getProjectIds().size() > 1) {
            return "MULTI";
        }
        return "TENANT".equalsIgnoreCase(workspace.getScopeType()) ? "ALL" : "SINGLE";
    }

    private List<AgentToolDescriptor> available(AgentRuntimeRecords.Workspace workspace) {
        List<AgentToolDescriptor> result = new ArrayList<>();
        for (AgentToolDescriptor descriptor : tools.values()) {
            if (!supportsSelection(descriptor, workspace)) {
                continue;
            }
            if (workspace != null && (isFinanceIntent(descriptor.getIntent()) || descriptor.getToolCode().startsWith("finance."))
                    && !Boolean.TRUE.equals(workspace.getFinanceEnabled())) {
                continue;
            }
            result.add(descriptor);
        }
        return result;
    }

    private int relevance(AgentToolDescriptor descriptor,
                          AgentTaskFrame frame,
                          AgentKnowledgeContext knowledgeContext) {
        int score = 0;
        if (knowledgeContext != null && (knowledgeContext.getToolHints().contains(descriptor.getToolCode())
                || knowledgeContext.getToolHints().contains(descriptor.getModelName()))) {
            score += 100;
        }
        if (frame.getMinimumRequiredTools() != null
                && frame.getMinimumRequiredTools().contains(descriptor.getToolCode())) {
            score += 200;
        }
        String domain = frame.getDomain() == null ? "GENERAL" : frame.getDomain();
        if (matchesDomain(descriptor.getIntent(), domain)) {
            score += 50;
        }
        String query = frame.getUserGoal() == null ? "" : frame.getUserGoal().toLowerCase(Locale.ROOT);
        for (String token : descriptor.getDescription().split("[、，。 ]")) {
            if (token.length() >= 2 && query.contains(token.toLowerCase(Locale.ROOT))) {
                score += 3;
            }
        }
        if (descriptor.getIntent() == AgentIntentType.HELP_KNOWLEDGE) {
            score += 2;
        }
        return score;
    }

    private boolean matchesDomain(AgentIntentType intent, String domain) {
        switch (domain) {
            case "FINANCE":
                return intent == AgentIntentType.FINANCE_RECEIVABLE_COLLECTION
                        || intent == AgentIntentType.FINANCE_ENTERPRISE_KPI
                        || intent == AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY
                        || intent == AgentIntentType.PROJECT_RECONCILIATION_DUE;
            case "SETTLEMENT":
                return intent == AgentIntentType.PROJECT_RECONCILIATION_DUE
                        || intent == AgentIntentType.PROJECT_ACTIVITY
                        || intent == AgentIntentType.DOCUMENT_SEARCH;
            case "INVENTORY":
                return intent == AgentIntentType.INVENTORY_SUMMARY
                        || intent == AgentIntentType.INVENTORY_OPERATIONS
                        || intent == AgentIntentType.INVENTORY_LEDGER_TRACE
                        || intent == AgentIntentType.PROJECT_MATERIAL_OCCUPANCY;
            case "MATERIAL":
                return intent == AgentIntentType.MATERIAL_ESTIMATE
                        || intent == AgentIntentType.PROJECT_RENT_MATERIALS
                        || intent == AgentIntentType.MATERIAL_LIFECYCLE
                        || intent == AgentIntentType.PROJECT_MATERIAL_OCCUPANCY;
            case "DOCUMENT":
                return intent == AgentIntentType.DOCUMENT_SEARCH
                        || intent == AgentIntentType.DOCUMENT_AUDIT
                        || intent == AgentIntentType.PROJECT_ACTIVITY;
            case "HELP":
                return intent == AgentIntentType.HELP_KNOWLEDGE;
            case "PROJECT":
                return intent == AgentIntentType.PROJECT_LIST
                        || intent == AgentIntentType.PROJECT_SUMMARY
                        || intent == AgentIntentType.PROJECT_CONTRACT_STATUS
                        || intent == AgentIntentType.CONTRACT_COMMERCIAL
                        || intent == AgentIntentType.PROJECT_MATERIAL_OCCUPANCY
                        || intent == AgentIntentType.PROJECT_ACTIVITY;
            default:
                return false;
        }
    }

    private List<Map<String, Object>> definitions(List<AgentToolDescriptor> descriptors) {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (AgentToolDescriptor descriptor : descriptors) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", descriptor.getModelName());
            AgentBusinessCapabilities.Definition business = AgentBusinessCapabilities.find(descriptor.getToolCode());
            String scopeNote = business != null && "PUBLIC_MARKET".equals(business.scope)
                    ? "本工具仅查询公开商城已发布信息，不受当前项目选择限制。"
                    : AgentToolDescriptor.SCOPE_IGNORES_SELECTION.equals(descriptor.getScopeBehavior())
                    ? "本工具按当前企业范围查询，忽略当前项目多选，回答时必须声明该口径。" : "";
            function.put("description", "canonicalToolCode=" + descriptor.getToolCode() + "。"
                    + descriptor.getDescription() + scopeNote);
            function.put("parameters", descriptor.getParameters());
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("type", "function");
            definition.put("function", function);
            definitions.add(definition);
        }
        return definitions;
    }

    private void register(String modelName,
                          String toolCode,
                          String description,
                          String riskLevel,
                          AgentIntentType intent,
                          String selectionSupport,
                          Map<String, Object> parameters) {
        register(modelName, toolCode, description, riskLevel, intent, selectionSupport,
                AgentToolDescriptor.SCOPE_RESPECTS_SELECTION, parameters);
    }

    private void register(String modelName,
                          String toolCode,
                          String description,
                          String riskLevel,
                          AgentIntentType intent,
                          String selectionSupport,
                          String scopeBehavior,
                          Map<String, Object> parameters) {
        AgentToolDescriptor descriptor = new AgentToolDescriptor(modelName, toolCode, description, riskLevel,
                intent, selectionSupport, scopeBehavior, parameters);
        tools.put(modelName, descriptor);
        registerAlias(modelName, descriptor);
        registerAlias(toolCode, descriptor);
        registerAlias(toolCode.replace('.', '_'), descriptor);
        if (intent != AgentIntentType.BUSINESS_QUERY) registerAlias(intent.name(), descriptor);
    }

    private void alias(String alias, String canonicalToolCode) {
        AgentToolDescriptor descriptor = aliases.get(normalizeAlias(canonicalToolCode));
        if (descriptor == null) {
            throw new IllegalStateException("Cannot register alias for unknown tool " + canonicalToolCode);
        }
        registerAlias(alias, descriptor);
    }

    private void registerAlias(String alias, AgentToolDescriptor descriptor) {
        String normalized = normalizeAlias(alias);
        AgentToolDescriptor existing = aliases.putIfAbsent(normalized, descriptor);
        if (existing != null && existing != descriptor) {
            throw new IllegalStateException("Duplicate Agent tool alias " + alias);
        }
    }

    private String normalizeAlias(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> defaultParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", property("string", "保留用户原始查询含义的简短说明"));
        return objectSchema(properties);
    }

    private Map<String, Object> financeParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", property("string", "用户原始查询的简短说明"));
        properties.put("asOfDate", property("string", "查询截止日期，格式 yyyy-MM-dd，不能晚于今天"));
        properties.put("year", property("integer", "统计年度，例如2026"));
        Map<String, Object> scope = property("string", "ALL_OVERDUE=本年及历史结转，DUE_IN_YEAR=仅本年，HISTORICAL_CARRYOVER=仅往年");
        scope.put("enum", Arrays.asList("ALL_OVERDUE", "DUE_IN_YEAR", "HISTORICAL_CARRYOVER"));
        properties.put("scope", scope);
        properties.put("minOutstandingAmount", property("number", "最低未清金额，单位元"));
        properties.put("minOverdueDays", property("integer", "仅用于应收账款的最低付款逾期天数；用户说未对账N天时禁止设置此字段"));
        properties.put("projectKeyword", property("string", "项目名称或项目编号关键词"));
        properties.put("customerKeyword", property("string", "客户名称关键词"));
        properties.put("managerName", property("string", "项目负责人姓名筛选"));
        Map<String, Object> priorities = property("array", "优先级筛选，可选P1/P2/P3");
        priorities.put("items", property("string", "P1、P2或P3"));
        properties.put("priorityLevels", priorities);
        Map<String, Object> sort = property("string", "排序方式");
        sort.put("enum", Arrays.asList("PRIORITY", "AMOUNT", "OVERDUE"));
        properties.put("sortBy", sort);
        properties.put("limit", property("integer", "完整产物最多返回项目数，默认1000，最大5000"));
        properties.put("includeClosedLate", property("boolean", "是否包含已结清但曾逾期项目；当前版本不支持，默认false"));
        return objectSchema(properties);
    }

    private Map<String, Object> financeLedgerParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", property("string", "用户原始查询的简短说明"));
        properties.put("asOfDate", property("string", "余额核算日期，格式yyyy-MM-dd，MVP仅允许本地今天"));
        properties.put("startDate", property("string", "登记实收实付统计开始日期，格式yyyy-MM-dd，必须与endDate同时提供"));
        properties.put("endDate", property("string", "登记实收实付统计结束日期，格式yyyy-MM-dd，不能晚于asOfDate"));
        properties.put("limit", property("integer", "结果卡最多展示项目数，默认20，最大100"));
        return objectSchema(properties);
    }

    private Map<String, Object> ownerActionCenterParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> riskTypes = property("array", "指定风险维度；不填表示行动中心全部可用维度");
        Map<String, Object> riskType = property("string", "风险维度");
        riskType.put("enum", Arrays.asList("CONTRACT_EXPIRED", "MATERIAL_OUTSTANDING", "UNRECONCILED",
                "OVERDUE_RECEIVABLE", "INACTIVE_PROJECT", "INVENTORY_ANOMALY", "PENDING_REVIEW"));
        riskTypes.put("items", riskType);
        properties.put("riskTypes", riskTypes);
        Map<String, Object> matchMode = property("string", "ANY表示命中任一指定风险；ALL表示同一项目必须同时命中全部指定风险");
        matchMode.put("enum", Arrays.asList("ANY", "ALL"));
        properties.put("matchMode", matchMode);
        Map<String, Object> projectStatus = property("string", "项目状态；默认ONGOING，只有用户明确要求时才查COMPLETED或ALL");
        projectStatus.put("enum", Arrays.asList("ONGOING", "COMPLETED", "ALL"));
        properties.put("projectStatus", projectStatus);
        properties.put("limit", property("integer", "项目榜与行动榜各自最多展示数量，默认20，最大100"));
        return objectSchema(properties);
    }

    private boolean isFinanceIntent(AgentIntentType intent) {
        return intent == AgentIntentType.FINANCE_RECEIVABLE_COLLECTION
                || intent == AgentIntentType.FINANCE_ENTERPRISE_KPI
                || intent == AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY;
    }

    private Map<String, Object> materialLifecycleParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> analysisType = property("string", "分析类型；ALL表示全部三类");
        analysisType.put("enum", Arrays.asList("ALL", "OVER_RETURN", "STALE_OCCUPANCY", "COMPENSATION_RATE"));
        properties.put("analysisType", analysisType);
        properties.put("minStagnantDays", property("integer", "长期只租不还的最低无归还天数，默认60"));
        properties.put("limit", property("integer", "每个分区最多展示条数，默认20，最大100"));
        return objectSchema(properties);
    }

    private Map<String, Object> contractCommercialParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> focus = property("string", "分析焦点；ALL表示两类都做");
        focus.put("enum", Arrays.asList("ALL", "COMPLETENESS", "PRICE_COMPARISON"));
        properties.put("focus", focus);
        properties.put("keyword", property("string", "项目名称、负责人或客户关键词"));
        properties.put("limit", property("integer", "每个分区最多展示条数，默认20，最大100"));
        return objectSchema(properties);
    }

    private Map<String, Object> documentAuditParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> documentTypes = property("array", "单据类型筛选；不填表示全部五类");
        Map<String, Object> documentType = property("string", "单据类型");
        documentType.put("enum", Arrays.asList("RENT_OUT", "RETURN", "COMPENSATION", "RENT_IN", "RENT_IN_RETURN"));
        documentTypes.put("items", documentType);
        properties.put("documentTypes", documentTypes);
        Map<String, Object> issueTypes = property("array", "审核规则筛选；不填表示全部（未复核、业务日期缺失）");
        Map<String, Object> issueType = property("string", "审核规则");
        issueType.put("enum", Arrays.asList("UNREVIEWED", "MISSING_DATE"));
        issueTypes.put("items", issueType);
        properties.put("issueTypes", issueTypes);
        properties.put("startDate", property("string", "业务日期开始日，格式yyyy-MM-dd"));
        properties.put("endDate", property("string", "业务日期结束日，格式yyyy-MM-dd"));
        properties.put("keyword", property("string", "单据名称关键词"));
        properties.put("limit", property("integer", "结果卡最多展示单据数，默认20，最大100"));
        return objectSchema(properties);
    }

    private Map<String, Object> inventoryOperationsParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> anomalyCode = property("string", "库存异常编码筛选；不填表示全部异常");
        anomalyCode.put("enum", Arrays.asList("NEGATIVE_INVENTORY", "NEGATIVE_RENTED",
                "NEGATIVE_LEASED", "LONG_TIME_NO_FLOW"));
        properties.put("anomalyCode", anomalyCode);
        Map<String, Object> severity = property("string", "异常等级筛选");
        severity.put("enum", Arrays.asList("HIGH", "LOW"));
        properties.put("severity", severity);
        properties.put("limit", property("integer", "结果卡最多展示异常数，默认20，最大100"));
        return objectSchema(properties);
    }

    private Map<String, Object> inventoryLedgerParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("materialKeyword", property("string", "材料名称或规格关键词，必须提供以唯一确定材料"));
        properties.put("materialId", property("string", "材料ID；已知时优先使用"));
        properties.put("startDate", property("string", "台账开始日期，格式yyyy-MM-dd"));
        properties.put("endDate", property("string", "台账结束日期，格式yyyy-MM-dd"));
        properties.put("behaviorTypes", property("string", "行为类型筛选，逗号分隔，如 rent,return"));
        properties.put("limit", property("integer", "结果卡最多展示流水条数，默认20，最大100"));
        return objectSchema(properties);
    }

    private Map<String, Object> projectListParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", property("string", "用户原始查询的简短说明"));
        properties.put("startDate", property("string", "项目录入开始日期，格式yyyy-MM-dd"));
        properties.put("endDate", property("string", "项目录入结束日期，格式yyyy-MM-dd"));
        Map<String, Object> businessType = property("string", "项目业务类型");
        businessType.put("enum", Arrays.asList("ALL", "rent_out", "rent_in"));
        properties.put("projectBusinessType", businessType);
        Map<String, Object> status = property("string", "项目状态");
        status.put("enum", Arrays.asList("ALL", "ONGOING", "COMPLETED"));
        properties.put("status", status);
        properties.put("managerName", property("string", "我方负责人登记姓名包含匹配；默认‘某人负责哪些项目’使用此字段。昵称原样查询，不推测全名"));
        properties.put("partnerName", property("string", "对方负责人登记姓名包含匹配；仅用户明确查询对方负责人时使用"));
        properties.put("keyword", property("string", "项目名称、负责人、合作单位或承租单位关键词"));
        properties.put("limit", property("integer", "结果卡最多展示项目数，默认100，最大500"));
        return objectSchema(properties);
    }

    private Map<String, Object> reconciliationDueParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("targetMonth", property("string", "目标月份，格式yyyy-MM，例如2026-08"));
        properties.put("asOfDate", property("string", "精确截止日期，格式yyyy-MM-dd；提供后优先于targetMonth"));
        properties.put("minUnreconciledDays", property("integer", "最低未对账天数，只用于最近财务对账截止日至asOfDate的缺口，不表示付款逾期"));
        properties.put("keyword", property("string", "项目名称、负责人、合作单位或承租单位关键词"));
        properties.put("limit", property("integer", "结果卡最多展示项目数，默认100，最大500"));
        return objectSchema(properties);
    }

    private Map<String, Object> contractStatusParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("asOfDate", property("string", "合同状态判断截止日期，格式yyyy-MM-dd，默认今天"));
        properties.put("expiryStartDate", property("string", "合同结束日期筛选开始日，格式yyyy-MM-dd；查询某月到期时与expiryEndDate同时提供"));
        properties.put("expiryEndDate", property("string", "合同结束日期筛选结束日，格式yyyy-MM-dd，包含当天；查询某月到期时与expiryStartDate同时提供"));
        Map<String, Object> status = property("string", "合同派生状态筛选");
        status.put("enum", Arrays.asList("ALL", "EXPIRED", "ACTIVE", "NOT_STARTED", "NO_END_DATE", "NO_CONTRACT"));
        properties.put("status", status);
        Map<String, Object> projectStatus = property("string", "项目状态筛选");
        projectStatus.put("enum", Arrays.asList("ALL", "ONGOING", "COMPLETED"));
        properties.put("projectStatus", projectStatus);
        properties.put("keyword", property("string", "项目名、负责人、客户或合同名关键词"));
        properties.put("limit", property("integer", "最多返回材料明细行数，默认100，最大500"));
        return objectSchema(properties);
    }

    private Map<String, Object> materialOccupancyParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> projectStatus = property("string", "项目状态筛选");
        projectStatus.put("enum", Arrays.asList("ALL", "ONGOING", "COMPLETED"));
        properties.put("projectStatus", projectStatus);
        properties.put("onlyWithOutstanding", property("boolean", "是否只返回仍有未归还材料的项目，默认true"));
        properties.put("keyword", property("string", "项目名称、负责人或客户关键词"));
        properties.put("limit", property("integer", "最多返回材料明细行数，默认100，最大500"));
        properties.put("offset", property("integer", "明细分页偏移，从0开始；不影响全量汇总"));
        properties.put("asOfDate", property("string", "余额截止日期；当前只支持今天"));
        return objectSchema(properties);
    }

    private Map<String, Object> materialTransactionAggregateParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> flowType = property("string", "材料业务流水类型");
        flowType.put("enum", Arrays.asList("RENT_OUT", "RETURN", "COMPENSATION", "RENT_IN", "RENT_IN_RETURN"));
        properties.put("flowType", flowType);
        properties.put("startDate", property("string", "业务日期开始日，格式yyyy-MM-dd，包含当天"));
        properties.put("endDate", property("string", "业务日期结束日，格式yyyy-MM-dd，包含当天"));
        Map<String, Object> groupBy = property("string", "MATERIAL_NAME默认将同名同单位材料跨规格合计；MATERIAL_SPEC按名称规格单位分别统计");
        groupBy.put("enum", Arrays.asList("MATERIAL_NAME", "MATERIAL_SPEC"));
        properties.put("groupBy", groupBy);
        Map<String, Object> metric = property("string", "QUANTITY按材料数量；DOCUMENT_COUNT按涉及单据数");
        metric.put("enum", Arrays.asList("QUANTITY", "DOCUMENT_COUNT"));
        properties.put("metric", metric);
        Map<String, Object> order = property("string", "排序方向");
        order.put("enum", Arrays.asList("DESC", "ASC"));
        properties.put("order", order);
        Map<String, Object> reviewScope = property("string", "复核范围，默认ALL统计全部已保存单据");
        reviewScope.put("enum", Arrays.asList("ALL", "REVIEWED", "UNREVIEWED"));
        properties.put("reviewScope", reviewScope);
        properties.put("materialKeyword", property("string", "材料名称或规格关键词"));
        properties.put("limit", property("integer", "返回排名数量，默认10，最大100"));
        return objectSchema(properties);
    }

    private Map<String, Object> projectActivityParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("asOfDate", property("string", "活动判断截止日期，格式yyyy-MM-dd，默认今天"));
        properties.put("minInactiveDays", property("integer", "最低无业务活动天数，例如30表示截至日期前连续30天无所选活动"));
        Map<String, Object> projectStatus = property("string", "项目状态筛选");
        projectStatus.put("enum", Arrays.asList("ALL", "ONGOING", "COMPLETED"));
        properties.put("projectStatus", projectStatus);
        Map<String, Object> activityTypes = property("array", "纳入判断的活动类型；不填表示全部");
        Map<String, Object> activityItem = property("string", "活动类型");
        activityItem.put("enum", Arrays.asList("RENT_OUT", "RETURN", "RENT_IN", "RENT_IN_RETURN", "MATERIAL_RECONCILIATION", "FINANCIAL_RECONCILIATION"));
        activityTypes.put("items", activityItem);
        properties.put("activityTypes", activityTypes);
        properties.put("onlyInactive", property("boolean", "是否只返回达到最低无活动天数的项目，默认true"));
        properties.put("keyword", property("string", "项目名称、负责人或客户关键词"));
        properties.put("limit", property("integer", "最多返回材料明细行数，默认100，最大500"));
        return objectSchema(properties);
    }

    private Map<String, Object> property(String type, String description) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type);
        value.put("description", description);
        return value;
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("additionalProperties", false);
        return parameters;
    }
}
