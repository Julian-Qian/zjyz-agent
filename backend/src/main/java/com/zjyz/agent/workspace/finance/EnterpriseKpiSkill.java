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
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EnterpriseKpiSkill {
    public static final String TOOL_CODE = "finance.enterprise_kpi";
    private final FinanceLedgerSnapshotService service;
    private final ObjectMapper objectMapper;

    public EnterpriseKpiSkill(FinanceLedgerSnapshotService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson, String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        requireFinance(workspace);
        Query query = FinanceLedgerQueryParser.parse(objectMapper, argumentsJson, originalMessage);
        Result result = service.generate(workspace, query, FinanceLedgerSnapshotService.LedgerDirection.BOTH);
        List<String> warnings = new ArrayList<>();
        warnings.add("本卡仅表示系统内正式结算本金和登记付款，不代表利润、营业额、完整现金流、资产或税务报表。");
        warnings.add("余额仅表示本地今天的当前台账快照；当前数据模型没有状态历史版本，不能重算历史余额。");
        warnings.add("登记实收实付来自系统付款记录，不代表银行实际到账或出账，也不等同于ACTIVE本金核销。");
        warnings.add("到期口径采用正式结算账期结束日 SETTLEMENT_PERIOD_END，不代表合同约定付款期限。");
        warnings.add("押金仅表示登记收取金额；系统没有押金退还台账，不能据此计算未退押金。");
        warnings.addAll(result.getWarnings());
        warnings = new ArrayList<>(new java.util.LinkedHashSet<>(warnings));

        int totalCount = result.getRentOut().getProjectCount() + result.getRentIn().getProjectCount();
        int limit = result.getQuery().getLimit();
        List<Map<String, Object>> items = new ArrayList<>();
        appendProjectItems(items, result.getRentOut(), limit);
        appendProjectItems(items, result.getRentIn(), limit);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "finance-enterprise-kpi");
        card.put("schemaVersion", "1.0");
        card.put("currency", "CNY");
        card.put("asOfDate", result.getQuery().getAsOfDate());
        card.put("startDate", result.getQuery().getStartDate());
        card.put("endDate", result.getQuery().getEndDate());
        card.put("requestedMetric", result.getQuery().getRequestedMetric());
        card.put("dueDateBasis", "SETTLEMENT_PERIOD_END");
        card.put("supplierNameSource", "CURRENT_PROJECT_PARTNER");
        card.put("rentOut", summaryWithoutProjects(result.getRentOut()));
        card.put("rentIn", summaryWithoutProjects(result.getRentIn()));
        card.put("items", items);
        card.put("totalCount", totalCount);
        card.put("displayedCount", items.size());
        card.put("truncated", totalCount > items.size());
        card.put("limit", limit);
        card.put("warnings", warnings);

        AgentEvidence evidence = evidence(result, totalCount);
        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("finance_enterprise_kpi");
        execution.setConfidence(1.0d);
        execution.setAnswer(buildAnswer(result));
        execution.setCards(Collections.singletonList(card));
        execution.setWarnings(warnings);
        execution.setEvidence(evidence);
        return execution;
    }

    private void appendProjectItems(List<Map<String, Object>> target, DirectionSummary summary, int limit) {
        if (summary == null || summary.getProjects() == null) {
            return;
        }
        for (ProjectSummary project : summary.getProjects()) {
            if (target.size() >= limit) {
                return;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("businessDirection", summary.getBusinessDirection());
            item.put("projectId", project.getProjectId());
            item.put("projectName", project.getProjectName());
            item.put("counterpartyName", project.getCounterpartyName());
            item.put("counterpartyNameSource", project.getCounterpartyNameSource());
            item.put("postedPrincipal", project.getPostedPrincipal());
            item.put("allocatedPrincipalAsOf", project.getAllocatedPrincipalAsOf());
            item.put("outstandingPrincipalAsOf", project.getOutstandingPrincipalAsOf());
            item.put("registeredCash", project.getRegisteredCash());
            target.add(item);
        }
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

    private String buildAnswer(Result result) {
        Query query = result.getQuery();
        if ("CUSTOMER_RENT_RECEIPTS".equals(query.getRequestedMetric())) {
            if (!StringUtils.hasText(query.getStartDate()) || !StringUtils.hasText(query.getEndDate())) {
                return "截至 " + query.getAsOfDate() + "，系统登记客户租金实收累计 "
                        + money(result.getRentOut().getCumulativeCash().getRent())
                        + " 元。该金额是付款登记，不等同于财务结算本金已核销金额。";
            }
            return query.getStartDate() + " 至 " + query.getEndDate() + "，系统登记客户租金实收 "
                    + money(result.getRentOut().getPeriodCash().getRent())
                    + " 元。该金额按付款日期统计，不等同于财务结算本金已核销金额。";
        }
        if ("CUSTOMER_REGISTERED_RECEIPTS".equals(query.getRequestedMetric())) {
            return registeredCashAnswer(query, result.getRentOut(), "客户登记实收");
        }
        if ("SUPPLIER_REGISTERED_PAYMENTS".equals(query.getRequestedMetric())) {
            return registeredCashAnswer(query, result.getRentIn(), "供应商登记实付");
        }
        DirectionSummary out = result.getRentOut();
        DirectionSummary in = result.getRentIn();
        String answer = "截至 " + query.getAsOfDate() + "，租出正式已对账应收本金 " + money(out.getPostedPrincipal())
                + " 元，其中ACTIVE本金核销 " + money(out.getAllocatedPrincipalAsOf())
                + " 元、未清 " + money(out.getOutstandingPrincipalAsOf())
                + " 元；客户登记实收 " + money(out.getCumulativeCash().getTotalRegistered())
                + " 元。租入正式已对账应付本金 " + money(in.getPostedPrincipal())
                + " 元，其中ACTIVE本金核销 " + money(in.getAllocatedPrincipalAsOf())
                + " 元、未付 " + money(in.getOutstandingPrincipalAsOf())
                + " 元；供应商登记实付 " + money(in.getCumulativeCash().getTotalRegistered())
                + " 元。登记实收实付与本金核销是不同口径。";
        if (StringUtils.hasText(query.getStartDate()) && StringUtils.hasText(query.getEndDate())) {
            answer += query.getStartDate() + " 至 " + query.getEndDate() + "，按payment_date统计的客户期间登记实收 "
                    + money(out.getPeriodCash().getTotalRegistered()) + " 元、供应商期间登记实付 "
                    + money(in.getPeriodCash().getTotalRegistered()) + " 元。";
        }
        return answer;
    }

    private String registeredCashAnswer(Query query, DirectionSummary summary, String label) {
        FinanceLedgerSnapshotModels.CashSummary cash = StringUtils.hasText(query.getStartDate())
                && StringUtils.hasText(query.getEndDate()) ? summary.getPeriodCash() : summary.getCumulativeCash();
        String period = StringUtils.hasText(query.getStartDate()) && StringUtils.hasText(query.getEndDate())
                ? query.getStartDate() + " 至 " + query.getEndDate() : "截至 " + query.getAsOfDate();
        return period + "，系统" + label + " " + money(cash.getTotalRegistered())
                + " 元，其中租金 " + money(cash.getRent()) + " 元、赔偿 " + money(cash.getCompensation())
                + " 元、押金 " + money(cash.getDeposit()) + " 元、滞纳金 " + money(cash.getLateFee())
                + " 元、其他 " + money(cash.getOther()) + " 元。以上按payment_date统计，"
                + "不代表银行实际到账或出账，也不等同于ACTIVE本金核销。";
    }

    private AgentEvidence evidence(Result result, int recordCount) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + result.getQuery().getAsOfDate());
        evidence.setSkills(Collections.singletonList("EnterpriseKpiSkill"));
        evidence.setApiList(Arrays.asList("internal:settlement_receivable_period",
                "internal:settlement_payable_period", "internal:customer_payment",
                "internal:supplier_payment", "internal:customer_payment_allocation",
                "internal:supplier_payment_allocation", "internal:project"));
        evidence.setRecordCount(recordCount);
        return evidence;
    }

    private void requireFinance(AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null || !Boolean.TRUE.equals(workspace.getFinanceEnabled())) {
            throw new MyBizException("企业财务分析能力尚未开通", "AGT403");
        }
    }

    private String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
