package com.zjyz.agent.workspace.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.DirectionSummary;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.ProjectSummary;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Query;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Result;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SupplierPayableSummarySkill {
    public static final String TOOL_CODE = "finance.supplier_payable_summary";
    private final FinanceLedgerSnapshotService service;
    private final ObjectMapper objectMapper;

    public SupplierPayableSummarySkill(FinanceLedgerSnapshotService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson, String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null || !Boolean.TRUE.equals(workspace.getFinanceEnabled())) {
            throw new MyBizException("企业财务分析能力尚未开通", "AGT403");
        }
        Query query = FinanceLedgerQueryParser.parse(objectMapper, argumentsJson, originalMessage);
        Result result = service.generate(workspace, query, FinanceLedgerSnapshotService.LedgerDirection.RENT_IN);
        DirectionSummary summary = result.getRentIn();
        int limit = query.getLimit();
        List<ProjectSummary> items = summary.getProjects().stream().limit(limit).collect(Collectors.toList());
        int totalCount = summary.getProjects().size();
        List<String> orderedWarnings = new ArrayList<>();
        orderedWarnings.add("本卡仅表示系统内正式应付本金和登记付款，不代表利润、资产、税务或银行流水。");
        orderedWarnings.add("余额仅表示本地今天的当前台账快照；当前数据模型没有状态历史版本，不能重算历史余额。");
        orderedWarnings.add("登记实付来自系统付款记录，不代表银行实际出账，也不等同于ACTIVE本金核销。");
        orderedWarnings.add("到期口径采用正式结算账期结束日 SETTLEMENT_PERIOD_END，不代表合同约定付款期限。");
        orderedWarnings.add("供应商名称取当前租入项目合作单位，仅作展示；应付本金按项目汇总，不做跨项目同名供应商合并。");
        orderedWarnings.addAll(result.getWarnings());
        List<String> warnings = new ArrayList<>(new java.util.LinkedHashSet<>(orderedWarnings));

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "supplier-payable-summary");
        card.put("schemaVersion", "1.0");
        card.put("currency", "CNY");
        card.put("asOfDate", query.getAsOfDate());
        card.put("startDate", query.getStartDate());
        card.put("endDate", query.getEndDate());
        card.put("requestedMetric", query.getRequestedMetric());
        card.put("dueDateBasis", "SETTLEMENT_PERIOD_END");
        card.put("supplierNameSource", "CURRENT_PROJECT_PARTNER");
        card.put("summary", summaryWithoutProjects(summary));
        card.put("items", items);
        card.put("totalCount", totalCount);
        card.put("displayedCount", items.size());
        card.put("truncated", totalCount > items.size());
        card.put("limit", limit);
        card.put("warnings", warnings);

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("finance_supplier_payable_summary");
        execution.setConfidence(1.0d);
        execution.setAnswer(buildAnswer(query, summary));
        execution.setCards(Collections.singletonList(card));
        execution.setWarnings(warnings);
        execution.setEvidence(evidence(query, totalCount));
        return execution;
    }

    private DirectionSummary summaryWithoutProjects(DirectionSummary source) {
        DirectionSummary target = new DirectionSummary();
        target.setBusinessDirection(source.getBusinessDirection());
        target.setPostedPrincipal(source.getPostedPrincipal());
        target.setAllocatedPrincipalAsOf(source.getAllocatedPrincipalAsOf());
        target.setOutstandingPrincipalAsOf(source.getOutstandingPrincipalAsOf());
        target.setDueAsOfOutstanding(source.getDueAsOfOutstanding());
        target.setOverdueOutstanding(source.getOverdueOutstanding());
        target.setDueTodayOutstanding(source.getDueTodayOutstanding());
        target.setCumulativeCash(source.getCumulativeCash());
        target.setPeriodCash(source.getPeriodCash());
        target.setProjectCount(source.getProjectCount());
        target.setPeriodCount(source.getPeriodCount());
        target.setProjects(Collections.emptyList());
        return target;
    }

    private String buildAnswer(Query query, DirectionSummary summary) {
        if ("SUPPLIER_REGISTERED_PAYMENTS".equals(query.getRequestedMetric())) {
            FinanceLedgerSnapshotModels.CashSummary cash = org.springframework.util.StringUtils.hasText(query.getStartDate())
                    && org.springframework.util.StringUtils.hasText(query.getEndDate())
                    ? summary.getPeriodCash() : summary.getCumulativeCash();
            String period = org.springframework.util.StringUtils.hasText(query.getStartDate())
                    && org.springframework.util.StringUtils.hasText(query.getEndDate())
                    ? query.getStartDate() + " 至 " + query.getEndDate() : "截至 " + query.getAsOfDate();
            return period + "，系统登记供应商实付 " + money(cash.getTotalRegistered())
                    + " 元，其中租金 " + money(cash.getRent()) + " 元、赔偿 " + money(cash.getCompensation())
                    + " 元、押金 " + money(cash.getDeposit()) + " 元、滞纳金 " + money(cash.getLateFee())
                    + " 元、其他 " + money(cash.getOther()) + " 元。以上按payment_date统计，"
                    + "不代表银行实际出账，也不等同于ACTIVE本金核销。";
        }
        String core = "截至 " + query.getAsOfDate() + "，租入项目正式已对账应付本金 "
                + money(summary.getPostedPrincipal()) + " 元，供应商登记实付 "
                + money(summary.getCumulativeCash().getTotalRegistered()) + " 元；其中ACTIVE本金核销 "
                + money(summary.getAllocatedPrincipalAsOf()) + " 元、未付本金 "
                + money(summary.getOutstandingPrincipalAsOf()) + " 元。";
        if ("DUE_PAYABLE".equals(query.getRequestedMetric())) {
            return core + "按系统账期结束日口径，已到期未付 " + money(summary.getDueAsOfOutstanding())
                    + " 元，其中严格逾期 " + money(summary.getOverdueOutstanding())
                    + " 元、今日到期 " + money(summary.getDueTodayOutstanding()) + " 元。";
        }
        return core + "登记实付与本金核销是不同口径，未分配本金付款为 "
                + money(summary.getCumulativeCash().getUnallocatedPrincipalAsOf()) + " 元。";
    }

    private AgentEvidence evidence(Query query, int recordCount) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + query.getAsOfDate());
        evidence.setSkills(Collections.singletonList("SupplierPayableSummarySkill"));
        evidence.setApiList(Arrays.asList("internal:settlement_payable_period",
                "internal:supplier_payment_allocation", "internal:supplier_payment", "internal:project"));
        evidence.setRecordCount(recordCount);
        return evidence;
    }

    private String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
