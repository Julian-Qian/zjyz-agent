package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentIntentType;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.orch.AgentSkillHandler;
import com.zjyz.agent.orch.AgentSlotBag;
import com.zjyz.agent.orch.AgentSlotExtractor;
import com.zjyz.agent.workspace.context.AgentTaskFrame;
import com.zjyz.agent.workspace.context.AgentProjectScopeSnapshot;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeContext;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.finance.ReceivableCollectionSkill;
import com.zjyz.agent.workspace.finance.EnterpriseKpiSkill;
import com.zjyz.agent.workspace.finance.SupplierPayableSummarySkill;
import com.zjyz.agent.workspace.risk.OwnerActionCenterSkill;
import com.zjyz.agent.workspace.service.AgentComputeSandbox;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.param.req.GenerateProjectReportParam;
import com.zjyz.pojo.param.ret.ProjectReportData;
import com.zjyz.service.ProjectReportService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AgentRuntimeToolRegistry {
    @org.springframework.beans.factory.annotation.Autowired
    private AgentBusinessQueryService businessQueryService;
    @org.springframework.beans.factory.annotation.Autowired
    private AgentDeterministicAnalytics deterministicAnalytics;

    private final Map<AgentIntentType, AgentSkillHandler> handlers = new EnumMap<>(AgentIntentType.class);
    private final AgentToolCatalog toolCatalog;
    private final AgentSlotExtractor slotExtractor;
    private final ProjectMapper projectMapper;
    private final AgentComputeSandbox computeSandbox;
    private final ProjectReportService projectReportService;
    private final ReceivableCollectionSkill receivableCollectionSkill;
    private final EnterpriseKpiSkill enterpriseKpiSkill;
    private final SupplierPayableSummarySkill supplierPayableSummarySkill;
    private final ProjectCatalogSkill projectCatalogSkill;
    private final ProjectReconciliationDueSkill projectReconciliationDueSkill;
    private final ProjectContractStatusSkill projectContractStatusSkill;
    private final ProjectMaterialOccupancySkill projectMaterialOccupancySkill;
    private final MaterialTransactionAggregateSkill materialTransactionAggregateSkill;
    private final ProjectActivitySkill projectActivitySkill;
    private final OwnerActionCenterSkill ownerActionCenterSkill;
    private final InventoryOperationsSkill inventoryOperationsSkill;
    private final InventoryLedgerTraceSkill inventoryLedgerTraceSkill;
    private final DocumentAuditSkill documentAuditSkill;
    private final MaterialLifecycleSkill materialLifecycleSkill;
    private final ContractCommercialSkill contractCommercialSkill;

    public AgentRuntimeToolRegistry(List<AgentSkillHandler> handlerList,
                                    AgentToolCatalog toolCatalog,
                                    AgentSlotExtractor slotExtractor,
                                    ProjectMapper projectMapper,
                                    AgentComputeSandbox computeSandbox,
                                    ProjectReportService projectReportService,
                                    ReceivableCollectionSkill receivableCollectionSkill,
                                    EnterpriseKpiSkill enterpriseKpiSkill,
                                    SupplierPayableSummarySkill supplierPayableSummarySkill,
                                    ProjectCatalogSkill projectCatalogSkill,
                                    ProjectReconciliationDueSkill projectReconciliationDueSkill,
                                    ProjectContractStatusSkill projectContractStatusSkill,
                                    ProjectMaterialOccupancySkill projectMaterialOccupancySkill,
                                    MaterialTransactionAggregateSkill materialTransactionAggregateSkill,
                                    ProjectActivitySkill projectActivitySkill,
                                    OwnerActionCenterSkill ownerActionCenterSkill,
                                    InventoryOperationsSkill inventoryOperationsSkill,
                                    InventoryLedgerTraceSkill inventoryLedgerTraceSkill,
                                    DocumentAuditSkill documentAuditSkill,
                                    MaterialLifecycleSkill materialLifecycleSkill,
                                    ContractCommercialSkill contractCommercialSkill) {
        if (handlerList != null) {
            for (AgentSkillHandler handler : handlerList) {
                handlers.put(handler.supportedIntent(), handler);
            }
        }
        this.toolCatalog = toolCatalog;
        this.slotExtractor = slotExtractor;
        this.projectMapper = projectMapper;
        this.computeSandbox = computeSandbox;
        this.projectReportService = projectReportService;
        this.receivableCollectionSkill = receivableCollectionSkill;
        this.enterpriseKpiSkill = enterpriseKpiSkill;
        this.supplierPayableSummarySkill = supplierPayableSummarySkill;
        this.projectCatalogSkill = projectCatalogSkill;
        this.projectReconciliationDueSkill = projectReconciliationDueSkill;
        this.projectContractStatusSkill = projectContractStatusSkill;
        this.projectMaterialOccupancySkill = projectMaterialOccupancySkill;
        this.materialTransactionAggregateSkill = materialTransactionAggregateSkill;
        this.projectActivitySkill = projectActivitySkill;
        this.ownerActionCenterSkill = ownerActionCenterSkill;
        this.inventoryOperationsSkill = inventoryOperationsSkill;
        this.inventoryLedgerTraceSkill = inventoryLedgerTraceSkill;
        this.documentAuditSkill = documentAuditSkill;
        this.materialLifecycleSkill = materialLifecycleSkill;
        this.contractCommercialSkill = contractCommercialSkill;
    }

    public List<Map<String, Object>> modelDefinitions() {
        return modelDefinitions(null);
    }

    public List<Map<String, Object>> modelDefinitions(AgentRuntimeRecords.Workspace workspace) {
        return toolCatalog.modelDefinitions(workspace);
    }

    public List<Map<String, Object>> modelDefinitions(AgentRuntimeRecords.Workspace workspace,
                                                       AgentTaskFrame taskFrame,
                                                       AgentKnowledgeContext knowledgeContext) {
        return toolCatalog.modelDefinitions(workspace, taskFrame, knowledgeContext);
    }

    public AgentSkillExecution execute(String modelToolName,
                                       String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        return execute(modelToolName, originalMessage, null, workspace);
    }

    public AgentSkillExecution execute(String modelToolName,
                                       String originalMessage,
                                       String argumentsJson,
                                       AgentRuntimeRecords.Workspace workspace) {
        AgentToolDescriptor descriptor = toolCatalog.find(modelToolName);
        if (descriptor == null) {
            throw new MyBizException("模型请求了未注册的工具", "AGT400");
        }
        if (workspace == null) {
            throw new MyBizException("工作空间上下文缺失", "AGT400");
        }
        if (!toolCatalog.supportsSelection(descriptor, workspace)) {
            throw new MyBizException("当前工具不适用于此工作空间范围", "AGT403");
        }
        if (isFinanceIntent(descriptor.getIntent())
                && !Boolean.TRUE.equals(workspace.getFinanceEnabled())) {
            throw new MyBizException("企业财务分析能力尚未开通", "AGT403");
        }
        if ("project.reconciliation_schedule".equals(descriptor.getToolCode())
                || "finance.material_settled_rent".equals(descriptor.getToolCode())) {
            return scopedExecution(descriptor, workspace, deterministicAnalytics.execute(descriptor.getToolCode(), argumentsJson, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.BUSINESS_QUERY) {
            return computeSandbox.executeWithoutCommit(() ->
                    businessQueryService.execute(descriptor.getToolCode(), argumentsJson, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.PROJECT_LIST) {
            return scopedExecution(descriptor, workspace,
                    projectCatalogSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.PROJECT_RECONCILIATION_DUE) {
            return scopedExecution(descriptor, workspace,
                    projectReconciliationDueSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.PROJECT_CONTRACT_STATUS) {
            return scopedExecution(descriptor, workspace,
                    projectContractStatusSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.PROJECT_MATERIAL_OCCUPANCY) {
            return scopedExecution(descriptor, workspace,
                    projectMaterialOccupancySkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.PROJECT_RENT_MATERIALS) {
            return scopedExecution(descriptor, workspace,
                    materialTransactionAggregateSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.PROJECT_ACTIVITY) {
            return scopedExecution(descriptor, workspace,
                    projectActivitySkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.MATERIAL_LIFECYCLE) {
            return scopedExecution(descriptor, workspace,
                    materialLifecycleSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.CONTRACT_COMMERCIAL) {
            return scopedExecution(descriptor, workspace,
                    contractCommercialSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.DOCUMENT_AUDIT) {
            return scopedExecution(descriptor, workspace,
                    documentAuditSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.INVENTORY_OPERATIONS) {
            return scopedExecution(descriptor, workspace,
                    inventoryOperationsSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.INVENTORY_LEDGER_TRACE) {
            return scopedExecution(descriptor, workspace,
                    inventoryLedgerTraceSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.RISK_OWNER_ACTION_CENTER) {
            return scopedExecution(descriptor, workspace,
                    ownerActionCenterSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.FINANCE_RECEIVABLE_COLLECTION) {
            return scopedExecution(descriptor, workspace,
                    receivableCollectionSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.FINANCE_ENTERPRISE_KPI) {
            return scopedExecution(descriptor, workspace,
                    enterpriseKpiSkill.execute(argumentsJson, originalMessage, workspace));
        }
        if (descriptor.getIntent() == AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY) {
            return scopedExecution(descriptor, workspace,
                    supplierPayableSummarySkill.execute(argumentsJson, originalMessage, workspace));
        }
        if ("SINGLE".equals(descriptor.getSelectionSupport()) && !StringUtils.hasText(workspace.getProjectId())) {
            throw new MyBizException("工作空间项目上下文缺失", "AGT400");
        }
        if (descriptor.getIntent() == AgentIntentType.PROJECT_SUMMARY) {
            return scopedExecution(descriptor, workspace, currentProjectSummary(originalMessage, workspace));
        }

        AgentSkillHandler handler = handlers.get(descriptor.getIntent());
        if (handler == null) {
            throw new MyBizException("工具暂不可用", "AGT503");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("projectId", workspace.getProjectId());
        context.put("projectBusinessType", workspace.getProjectBusinessType());
        AgentSlotBag slots = slotExtractor.extract(originalMessage, context, descriptor.getIntent());
        slots.setProjectId(workspace.getProjectId());
        slots.setProjectBusinessType(workspace.getProjectBusinessType());
        if (descriptor.getIntent() == AgentIntentType.MATERIAL_ESTIMATE) {
            return scopedExecution(descriptor, workspace,
                    computeSandbox.executeWithoutCommit(() -> handler.execute(originalMessage, context, slots)));
        }
        return scopedExecution(descriptor, workspace, handler.execute(originalMessage, context, slots));
    }

    public AgentSkillExecution execute(AgentIntentType intent,
                                       String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        AgentToolDescriptor descriptor = toolCatalog.find(intent, workspace);
        if (descriptor != null) {
            return execute(descriptor.getModelName(), originalMessage, null, workspace);
        }
        if (intent == AgentIntentType.PROJECT_SUMMARY && workspace != null
                && !"SINGLE".equals(toolCatalog.selectionKind(workspace))) {
            AgentToolDescriptor projectList = toolCatalog.find(AgentIntentType.PROJECT_LIST, workspace);
            return execute(projectList.getModelName(), originalMessage, null, workspace);
        }
        throw new MyBizException("未注册的确定性工具", "AGT400");
    }

    public String toolCode(String modelToolName) {
        return toolCatalog.canonicalToolCode(modelToolName);
    }

    public String riskLevel(String modelToolName) {
        AgentToolDescriptor descriptor = toolCatalog.find(modelToolName);
        return descriptor == null ? "READ" : descriptor.getRiskLevel();
    }

    public String displayName(String modelToolName) {
        AgentToolDescriptor descriptor = toolCatalog.find(modelToolName);
        return descriptor == null ? modelToolName : descriptor.getDescription();
    }

    public String toolCode(AgentIntentType intent) {
        for (AgentToolDescriptor descriptor : toolCatalog.descriptors()) {
            if (descriptor.getIntent() == intent) {
                return descriptor.getToolCode();
            }
        }
        return intent == null ? "unknown" : intent.name().toLowerCase();
    }

    private AgentSkillExecution scopedExecution(AgentToolDescriptor descriptor,
                                                AgentRuntimeRecords.Workspace workspace,
                                                AgentSkillExecution execution) {
        if (descriptor.getIntent() == AgentIntentType.HELP_KNOWLEDGE && workspace.getProjectIds() != null
                && !workspace.getProjectIds().isEmpty()) {
            AgentProjectScopeSnapshot.capture(workspace).validateExecution(execution, false);
        }
        if (!AgentToolDescriptor.SCOPE_RESPECTS_SELECTION.equals(descriptor.getScopeBehavior())) {
            AgentEvidence global = execution.getEvidence() == null ? new AgentEvidence() : execution.getEvidence();
            global.setToolCode(descriptor.getToolCode());
            global.setScopeType(AgentToolDescriptor.SCOPE_NOT_APPLICABLE.equals(descriptor.getScopeBehavior())
                    ? "NOT_APPLICABLE" : "TENANT_INVENTORY");
            global.setSelectionMode("NOT_APPLICABLE"); global.setProjectIds(Collections.emptyList());
            execution.setEvidence(global); return execution;
        }
        AgentProjectScopeSnapshot scope = AgentProjectScopeSnapshot.capture(workspace);
        scope.validateExecution(execution, descriptor.getIntent() != AgentIntentType.HELP_KNOWLEDGE);
        AgentEvidence evidence = execution.getEvidence();
        if (evidence == null) {
            evidence = new AgentEvidence();
            execution.setEvidence(evidence);
        }
        evidence.setToolCode(descriptor.getToolCode());
        evidence.setScopeType(scope.getScopeType());
        evidence.setSelectionMode(scope.getSelectionMode());
        evidence.setProjectIds(scope.getProjectIds());
        return execution;
    }

    private boolean isFinanceIntent(AgentIntentType intent) {
        return intent == AgentIntentType.FINANCE_RECEIVABLE_COLLECTION
                || intent == AgentIntentType.FINANCE_ENTERPRISE_KPI
                || intent == AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY;
    }

    private AgentSkillExecution currentProjectSummary(String originalMessage,
                                                      AgentRuntimeRecords.Workspace workspace) {
        ProjectEntity project = projectMapper.selectOne(new QueryWrapper<ProjectEntity>()
                .eq("cid", workspace.getCid())
                .eq("project_id", workspace.getProjectId())
                .last("LIMIT 1"));
        if (project == null) {
            throw new MyBizException("项目不存在或无权限", "PRCT404");
        }
        if ("rent_in".equalsIgnoreCase(project.getProjectBusinessType())) {
            return rentInOperatingScopeNotice(project, workspace);
        }

        GenerateProjectReportParam param = resolveReportPeriod(originalMessage, project.getProjectId());
        ProjectReportData data = projectReportService.generateSnapshot(param);
        ProjectReportData.Head head = data.getHead();
        ProjectReportData.RentalSummary rental = data.getRentalSummary();
        ProjectReportData.FeeSummary fees = data.getFeeSummary();
        ProjectReportData.PaymentSummary payments = data.getPaymentSummary();

        Double collectionRate = percentage(payments.getAccumulatedReceived(), payments.getAccumulatedReceivable());
        List<Map<String, Object>> risks = buildRisks(data, collectionRate);
        List<Map<String, Object>> actions = buildActions(risks);
        String healthLevel = healthLevel(risks);
        String healthLabel = healthLabel(healthLevel);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "project-operating-report");
        card.put("schemaVersion", "1.0");
        card.put("projectId", project.getProjectId());
        card.put("projectName", project.getProjectName());
        card.put("customerName", head.getCustomerName());
        card.put("projectBusinessType", project.getProjectBusinessType());
        card.put("projectStatusFlag", project.getProjectStatusFlag());
        card.put("managerName", project.getManagerName());
        card.put("period", periodCard(head));
        card.put("health", healthCard(healthLevel, healthLabel, risks));
        card.put("kpis", buildKpis(rental, fees, payments, collectionRate, head.getUnsettledDays()));
        card.put("operations", operationsCard(rental));
        card.put("fees", feesCard(fees));
        card.put("payments", paymentsCard(payments, collectionRate));
        card.put("materialTop", data.getMaterialTop());
        card.put("risks", risks);
        card.put("actions", actions);
        card.put("scopeNote", "金额按已生成财务对账单统计；押金单独列示，不计入累计欠款。");

        String answer = buildOperatingAnswer(data, collectionRate, healthLabel, risks);
        card.put("summary", answer);

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange(head.getStartDate() + "~" + head.getEndDate());
        evidence.setSkills(Collections.singletonList("ProjectOperatingReportSkill"));
        evidence.setApiList(java.util.Arrays.asList(
                "internal:ProjectReportService.generateSnapshot",
                "/contract/queryContract",
                "/settlementDocument/querySettlementDocumentInfo"));
        evidence.setRecordCount(operatingRecordCount(data));

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("project_summary");
        execution.setConfidence(1.0d);
        execution.setAnswer(answer);
        execution.setCards(Collections.singletonList(card));
        execution.setWarnings(buildWarnings(data));
        execution.setEvidence(evidence);
        return execution;
    }

    private AgentSkillExecution rentInOperatingScopeNotice(ProjectEntity project,
                                                            AgentRuntimeRecords.Workspace workspace) {
        String answer = "“" + safe(project.getProjectName())
                + "”是租入项目。当前项目经营快照采用租出侧的在租、应收和客户回款口径，"
                + "不能用于判断租入项目的应付、供应商付款和退租成本，因此本次没有输出可能误导的经营数字。";
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "project-operating-report");
        card.put("schemaVersion", "1.0");
        card.put("dataAvailable", false);
        card.put("projectId", project.getProjectId());
        card.put("projectName", project.getProjectName());
        card.put("projectBusinessType", project.getProjectBusinessType());
        card.put("health", healthCard("UNKNOWN", "口径待补", Collections.emptyList()));
        card.put("scopeNote", "租入项目需要使用租入、退租、应付结算和供应商付款口径，当前版本暂未聚合这些数据。");
        card.put("summary", answer);

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + LocalDate.now());
        evidence.setSkills(Collections.singletonList("ProjectOperatingReportSkill"));
        evidence.setApiList(Collections.singletonList("/agent/workspaces/" + workspace.getWorkspaceId()));
        evidence.setRecordCount(1);

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("project_summary");
        execution.setConfidence(1.0d);
        execution.setAnswer(answer);
        execution.setCards(Collections.singletonList(card));
        execution.setWarnings(Collections.singletonList("租入项目经营口径暂未接入，未输出应付或成本数字。"));
        execution.setEvidence(evidence);
        return execution;
    }

    private GenerateProjectReportParam resolveReportPeriod(String message, String projectId) {
        LocalDate today = LocalDate.now();
        GenerateProjectReportParam param = new GenerateProjectReportParam();
        param.setProjectId(projectId);
        if (containsAny(message, "累计", "至今", "整个项目", "全周期", "整体经营")) {
            param.setPeriodType("TO_DATE");
            param.setEndDate(today.toString());
            return param;
        }

        LocalDate start = today.withDayOfMonth(1);
        if (containsAny(message, "本季度", "季度")) {
            int firstMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
            start = LocalDate.of(today.getYear(), firstMonth, 1);
            param.setPeriodType("QUARTER");
        } else if (containsAny(message, "今年", "本年", "年度")) {
            start = LocalDate.of(today.getYear(), 1, 1);
            param.setPeriodType("CUSTOM");
        } else if (containsAny(message, "近30天", "最近30天", "过去30天")) {
            start = today.minusDays(29);
            param.setPeriodType("CUSTOM");
        } else {
            param.setPeriodType("MONTH");
        }
        param.setStartDate(start.toString());
        param.setEndDate(today.toString());
        return param;
    }

    private Map<String, Object> periodCard(ProjectReportData.Head head) {
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("type", head.getPeriodType());
        period.put("startDate", head.getStartDate());
        period.put("endDate", head.getEndDate());
        period.put("settledUntil", head.getSettledUntil());
        period.put("unsettledDays", safeInt(head.getUnsettledDays()));
        return period;
    }

    private Map<String, Object> healthCard(String level,
                                           String label,
                                           List<Map<String, Object>> risks) {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("level", level);
        health.put("label", label);
        health.put("riskCount", risks.size());
        health.put("summary", risks.isEmpty() ? "当前未识别到需要优先处理的经营风险" : "当前识别 " + risks.size() + " 项经营风险");
        return health;
    }

    private List<Map<String, Object>> buildKpis(ProjectReportData.RentalSummary rental,
                                                 ProjectReportData.FeeSummary fees,
                                                 ProjectReportData.PaymentSummary payments,
                                                 Double collectionRate,
                                                 Integer unsettledDays) {
        List<Map<String, Object>> kpis = new ArrayList<>();
        kpis.add(kpi("closingRentedQuantity", "期末在租", safeLong(rental.getClosingRentedQuantity()),
                number(rental.getClosingRentedQuantity()), "件", "neutral"));
        kpis.add(kpi("periodTotalFee", "本期已对账费用", safeDouble(fees.getPeriodTotalFee()),
                money(fees.getPeriodTotalFee()), "元", "neutral"));
        kpis.add(kpi("periodPaymentTotal", "本期收款", safeDouble(payments.getPeriodPaymentTotal()),
                money(payments.getPeriodPaymentTotal()), "元", "neutral"));

        double arrears = safeDouble(payments.getArrearsBalance());
        String arrearsLabel = arrears < 0 ? "预收余额" : "累计欠款";
        kpis.add(kpi("arrearsBalance", arrearsLabel, Math.abs(arrears), money(Math.abs(arrears)), "元",
                arrears > 0 ? "danger" : "success"));
        kpis.add(kpi("collectionRate", "累计回款率", collectionRate,
                collectionRate == null ? "--" : percent(collectionRate), "%", collectionRate != null && collectionRate < 0.8d ? "warning" : "success"));
        kpis.add(kpi("unsettledDays", "未对账天数", safeInt(unsettledDays),
                String.valueOf(safeInt(unsettledDays)), "天", safeInt(unsettledDays) > 0 ? "warning" : "success"));
        return kpis;
    }

    private Map<String, Object> kpi(String code,
                                    String label,
                                    Object value,
                                    String displayValue,
                                    String unit,
                                    String tone) {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("code", code);
        kpi.put("label", label);
        kpi.put("value", value);
        kpi.put("displayValue", displayValue);
        kpi.put("unit", unit);
        kpi.put("tone", tone);
        return kpi;
    }

    private Map<String, Object> operationsCard(ProjectReportData.RentalSummary rental) {
        Map<String, Object> operations = new LinkedHashMap<>();
        operations.put("openingRentedQuantity", safeLong(rental.getOpeningRentedQuantity()));
        operations.put("closingRentedQuantity", safeLong(rental.getClosingRentedQuantity()));
        operations.put("rentDocumentCount", safeInt(rental.getRentDocumentCount()));
        operations.put("rentQuantity", safeLong(rental.getRentQuantity()));
        operations.put("returnDocumentCount", safeInt(rental.getReturnDocumentCount()));
        operations.put("returnQuantity", safeLong(rental.getReturnQuantity()));
        operations.put("compensationDocumentCount", safeInt(rental.getCompensationDocumentCount()));
        operations.put("compensationQuantity", safeLong(rental.getCompensationQuantity()));
        return operations;
    }

    private Map<String, Object> feesCard(ProjectReportData.FeeSummary fees) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settlementDocumentCount", fees.getSettlementRows() == null ? 0 : fees.getSettlementRows().size());
        result.put("periodRentFee", safeDouble(fees.getPeriodRentFee()));
        result.put("periodCompensationFee", safeDouble(fees.getPeriodCompensationFee()));
        result.put("periodIncidentalFee", safeDouble(fees.getPeriodIncidentalFee()));
        result.put("periodOtherFee", safeDouble(fees.getPeriodOtherFee()));
        result.put("periodTotalFee", safeDouble(fees.getPeriodTotalFee()));
        return result;
    }

    private Map<String, Object> paymentsCard(ProjectReportData.PaymentSummary payments, Double collectionRate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("periodPaymentTotal", safeDouble(payments.getPeriodPaymentTotal()));
        result.put("periodPaymentByType", payments.getPeriodPaymentByType());
        result.put("accumulatedReceivable", safeDouble(payments.getAccumulatedReceivable()));
        result.put("accumulatedReceived", safeDouble(payments.getAccumulatedReceived()));
        result.put("arrearsBalance", safeDouble(payments.getArrearsBalance()));
        result.put("depositTotal", safeDouble(payments.getDepositTotal()));
        result.put("collectionRate", collectionRate);
        return result;
    }

    private List<Map<String, Object>> buildRisks(ProjectReportData data, Double collectionRate) {
        List<Map<String, Object>> risks = new ArrayList<>();
        if (data.getAnomalies() == null) {
            return risks;
        }
        for (String anomaly : data.getAnomalies()) {
            if (!StringUtils.hasText(anomaly)) {
                continue;
            }
            String code = "DATA_ANOMALY";
            String title = "经营数据需要核查";
            String level = "MEDIUM";
            if (anomaly.contains("合同已于")) {
                code = "CONTRACT_OVERDUE";
                title = "合同已经到期";
                level = safeLong(data.getRentalSummary().getClosingRentedQuantity()) > 0 ? "HIGH" : "MEDIUM";
            } else if (anomaly.contains("欠款余额")) {
                code = "ARREARS";
                title = "存在未回款金额";
                level = collectionRate != null && collectionRate < 0.5d ? "HIGH" : "MEDIUM";
            } else if (anomaly.contains("未生成财务对账单")) {
                code = "UNSETTLED_PERIOD";
                title = "存在未对账区间";
                level = safeInt(data.getHead().getUnsettledDays()) > 30 ? "HIGH" : "MEDIUM";
            } else if (anomaly.contains("在租数量为负数") || anomaly.contains("读取失败")) {
                code = "DATA_INTEGRITY";
                title = "经营数据完整性异常";
                level = "HIGH";
            } else if (anomaly.contains("无租出、归还流水")) {
                code = "NO_ACTIVITY";
                title = "统计期内没有租还流水";
                level = "INFO";
            }
            Map<String, Object> risk = new LinkedHashMap<>();
            risk.put("code", code);
            risk.put("level", level);
            risk.put("title", title);
            risk.put("detail", anomaly);
            risks.add(risk);
        }
        return risks;
    }

    private List<Map<String, Object>> buildActions(List<Map<String, Object>> risks) {
        List<Map<String, Object>> actions = new ArrayList<>();
        int priority = 1;
        for (Map<String, Object> risk : risks) {
            String code = String.valueOf(risk.get("code"));
            String title;
            if ("ARREARS".equals(code)) {
                title = "联系客户确认回款计划，并核对收款登记";
            } else if ("UNSETTLED_PERIOD".equals(code)) {
                title = "补齐最新财务对账单，确认尚未入账的租金";
            } else if ("CONTRACT_OVERDUE".equals(code)) {
                title = "确认合同续签或制定剩余材料回收计划";
            } else if ("DATA_INTEGRITY".equals(code)) {
                title = "核对异常单据和材料台账后重新生成报告";
            } else if ("NO_ACTIVITY".equals(code)) {
                title = "确认项目本期是否停滞或单据尚未录入";
            } else {
                title = "核查经营报告中的数据异常";
            }
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("priority", priority++);
            action.put("title", title);
            action.put("riskCode", code);
            actions.add(action);
            if (actions.size() >= 4) {
                break;
            }
        }
        if (actions.isEmpty()) {
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("priority", 1);
            action.put("title", "保持当前对账和回款节奏，持续关注在租材料变化");
            action.put("riskCode", "ROUTINE_MONITORING");
            actions.add(action);
        }
        return actions;
    }

    private String buildOperatingAnswer(ProjectReportData data,
                                        Double collectionRate,
                                        String healthLabel,
                                        List<Map<String, Object>> risks) {
        ProjectReportData.Head head = data.getHead();
        ProjectReportData.RentalSummary rental = data.getRentalSummary();
        ProjectReportData.FeeSummary fees = data.getFeeSummary();
        ProjectReportData.PaymentSummary payments = data.getPaymentSummary();
        StringBuilder answer = new StringBuilder();
        answer.append("“").append(safe(head.getProjectName())).append("”项目在 ")
                .append(head.getStartDate()).append(" 至 ").append(head.getEndDate())
                .append(" 的经营状态为").append(healthLabel).append("。期末在租 ")
                .append(number(rental.getClosingRentedQuantity())).append(" 件，本期已对账费用 ")
                .append(money(fees.getPeriodTotalFee())).append(" 元，本期收款 ")
                .append(money(payments.getPeriodPaymentTotal())).append(" 元。");

        answer.append("截至期末累计应收 ").append(money(payments.getAccumulatedReceivable()))
                .append(" 元、累计实收 ").append(money(payments.getAccumulatedReceived())).append(" 元，");
        double arrears = safeDouble(payments.getArrearsBalance());
        if (arrears >= 0) {
            answer.append("欠款 ").append(money(arrears)).append(" 元");
        } else {
            answer.append("预收 ").append(money(Math.abs(arrears))).append(" 元");
        }
        if (collectionRate != null) {
            answer.append("，累计回款率 ").append(percent(collectionRate)).append("%");
        }
        answer.append("。");
        if (risks.isEmpty()) {
            answer.append("当前未识别到需要优先处理的经营风险。");
        } else {
            answer.append("当前识别 ").append(risks.size()).append(" 项风险，优先关注：")
                    .append(risks.get(0).get("title")).append("。");
        }
        answer.append("金额采用已对账口径，仍有 ")
                .append(safeInt(head.getUnsettledDays())).append(" 天未对账。");
        return answer.toString();
    }

    private List<String> buildWarnings(ProjectReportData data) {
        List<String> warnings = new ArrayList<>();
        warnings.add("金额仅统计已生成财务对账单的部分，未对账区间不包含在本期费用中。");
        if (data.getAnomalies() != null) {
            warnings.addAll(data.getAnomalies());
        }
        return warnings;
    }

    private int operatingRecordCount(ProjectReportData data) {
        ProjectReportData.RentalSummary rental = data.getRentalSummary();
        int count = safeInt(rental.getRentDocumentCount())
                + safeInt(rental.getReturnDocumentCount())
                + safeInt(rental.getCompensationDocumentCount());
        count += data.getFeeSummary().getSettlementRows() == null ? 0 : data.getFeeSummary().getSettlementRows().size();
        count += data.getPaymentSummary().getPeriodPaymentByType() == null ? 0 : data.getPaymentSummary().getPeriodPaymentByType().size();
        return Math.max(count, 1);
    }

    private String healthLevel(List<Map<String, Object>> risks) {
        boolean hasMedium = false;
        for (Map<String, Object> risk : risks) {
            String level = String.valueOf(risk.get("level"));
            if ("HIGH".equals(level)) {
                return "RED";
            }
            if ("MEDIUM".equals(level)) {
                hasMedium = true;
            }
        }
        return hasMedium ? "YELLOW" : "GREEN";
    }

    private String healthLabel(String level) {
        if ("RED".equals(level)) {
            return "红色预警";
        }
        if ("YELLOW".equals(level)) {
            return "黄色预警";
        }
        return "正常";
    }

    private Double percentage(Double received, Double receivable) {
        double base = safeDouble(receivable);
        if (base <= 0d) {
            return null;
        }
        return safeDouble(received) / base;
    }

    private String money(Double value) {
        return String.format(Locale.ROOT, "%,.2f", safeDouble(value));
    }

    private String money(double value) {
        return String.format(Locale.ROOT, "%,.2f", value);
    }

    private String number(Long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(safeLong(value));
    }

    private String percent(Double value) {
        return String.format(Locale.ROOT, "%.1f", safeDouble(value) * 100d);
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private double safeDouble(Double value) {
        return value == null ? 0d : value;
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
