package com.zjyz.agent.workspace.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.CashSummary;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.DirectionSummary;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.ProjectSummary;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Query;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Result;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinanceLedgerSkillsTest {

    @Test
    @SuppressWarnings("unchecked")
    void enterpriseCardKeepsCashAndPrincipalSeparateWithoutUnsupportedMetrics() {
        FinanceLedgerSnapshotService service = mock(FinanceLedgerSnapshotService.class);
        when(service.generate(any(), any(), eq(FinanceLedgerSnapshotService.LedgerDirection.BOTH))).thenReturn(result());
        EnterpriseKpiSkill skill = new EnterpriseKpiSkill(service, new ObjectMapper());

        AgentSkillExecution execution = skill.execute("{\"asOfDate\":\"2026-08-31\"}",
                "公司现在应收应付、已收已付、未清多少", workspace(true));

        Map<String, Object> card = (Map<String, Object>) execution.getCards().get(0);
        assertEquals("finance-enterprise-kpi", card.get("type"));
        assertEquals("CNY", card.get("currency"));
        assertEquals("SETTLEMENT_PERIOD_END", card.get("dueDateBasis"));
        assertFalse(card.containsKey("profit"));
        assertFalse(card.containsKey("revenue"));
        assertFalse(card.containsKey("netCashFlow"));
        assertEquals(Collections.emptyList(), ((DirectionSummary) card.get("rentOut")).getProjects());
        assertEquals(Collections.emptyList(), ((DirectionSummary) card.get("rentIn")).getProjects());
        assertTrue(execution.getEvidence().getApiList().contains("internal:settlement_receivable_period"));
        assertTrue(execution.getEvidence().getApiList().contains("internal:settlement_payable_period"));
        assertTrue(execution.getEvidence().getApiList().contains("internal:customer_payment"));
        assertTrue(execution.getEvidence().getApiList().contains("internal:supplier_payment"));
        assertTrue(execution.getAnswer().contains("登记实收实付与本金核销是不同口径"));
        assertTrue(execution.getWarnings().stream().anyMatch(value -> value.contains("未退押金")));
    }

    @Test
    void rentReceiptQuestionUsesPaymentDatePeriodMetric() {
        FinanceLedgerSnapshotService service = mock(FinanceLedgerSnapshotService.class);
        Result result = result();
        result.getQuery().setStartDate("2026-07-01");
        result.getQuery().setEndDate("2026-07-31");
        result.getQuery().setRequestedMetric("CUSTOMER_RENT_RECEIPTS");
        result.getRentOut().getPeriodCash().setRent(new BigDecimal("1234.56"));
        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        when(service.generate(any(), queryCaptor.capture(),
                eq(FinanceLedgerSnapshotService.LedgerDirection.BOTH))).thenReturn(result);

        AgentSkillExecution execution = new EnterpriseKpiSkill(service, new ObjectMapper()).execute(
                "{}",
                "上个月收了多少租金", workspace(true));

        YearMonth previousMonth = YearMonth.from(LocalDate.now()).minusMonths(1);
        assertEquals(previousMonth.atDay(1).toString(), queryCaptor.getValue().getStartDate());
        assertEquals(previousMonth.atEndOfMonth().toString(), queryCaptor.getValue().getEndDate());
        assertEquals("CUSTOMER_RENT_RECEIPTS", queryCaptor.getValue().getRequestedMetric());
        assertTrue(execution.getAnswer().contains("1234.56"));
        assertTrue(execution.getAnswer().contains("按付款日期统计"));
    }

    @Test
    void enterpriseSingleDirectionCashAnswersUseRegisteredPaymentsWithoutClaimingBankFlow() {
        FinanceLedgerSnapshotService service = mock(FinanceLedgerSnapshotService.class);
        when(service.generate(any(), any(), eq(FinanceLedgerSnapshotService.LedgerDirection.BOTH))).thenAnswer(invocation -> {
            Result generated = result();
            Query parsed = invocation.getArgument(1);
            if (parsed.getLimit() == null) parsed.setLimit(20);
            generated.setQuery(parsed);
            return generated;
        });
        EnterpriseKpiSkill skill = new EnterpriseKpiSkill(service, new ObjectMapper());

        AgentSkillExecution receipts = skill.execute("{\"asOfDate\":\"2026-08-31\"}",
                "公司回款多少", workspace(true));
        AgentSkillExecution payments = skill.execute("{\"asOfDate\":\"2026-08-31\"}",
                "全部项目已付多少钱", workspace(true));

        assertTrue(receipts.getAnswer().contains("客户登记实收 7000.00 元"));
        assertTrue(payments.getAnswer().contains("供应商登记实付 5200.00 元"));
        assertTrue(receipts.getAnswer().contains("不代表银行实际到账或出账"));
        assertTrue(payments.getAnswer().contains("不等同于ACTIVE本金核销"));
    }

    @Test
    void singleProjectCompositeReceivableAnswerIncludesPostedCashAndOutstanding() {
        FinanceLedgerSnapshotService service = mock(FinanceLedgerSnapshotService.class);
        when(service.generate(any(), any(), eq(FinanceLedgerSnapshotService.LedgerDirection.BOTH)))
                .thenAnswer(invocation -> {
                    Result generated = result();
                    Query parsed = invocation.getArgument(1);
                    if (parsed.getLimit() == null) parsed.setLimit(20);
                    generated.setQuery(parsed);
                    return generated;
                });

        AgentSkillExecution execution = new EnterpriseKpiSkill(service, new ObjectMapper()).execute(
                "{\"asOfDate\":\"2026-08-31\"}",
                "这个项目现在应收、已收和未清多少？", workspace(true));

        assertTrue(execution.getAnswer().contains("应收本金 12000.00 元"));
        assertTrue(execution.getAnswer().contains("客户登记实收 7000.00 元"));
        assertTrue(execution.getAnswer().contains("未清 6500.00 元"));
    }

    @Test
    void historicalRegisteredCashAnswersUsePeriodCashInsteadOfCumulativeCash() {
        FinanceLedgerSnapshotService service = mock(FinanceLedgerSnapshotService.class);
        when(service.generate(any(), any(), any())).thenAnswer(invocation -> {
            Result generated = result();
            Query parsed = invocation.getArgument(1);
            if (parsed.getLimit() == null) parsed.setLimit(20);
            generated.setQuery(parsed);
            generated.getRentOut().getPeriodCash().setTotalRegistered(new BigDecimal("1234.56"));
            generated.getRentIn().getPeriodCash().setTotalRegistered(new BigDecimal("2345.67"));
            return generated;
        });

        AgentSkillExecution receipts = new EnterpriseKpiSkill(service, new ObjectMapper())
                .execute("{}", "上个月公司已收多少", workspace(true));
        AgentSkillExecution payments = new EnterpriseKpiSkill(service, new ObjectMapper())
                .execute("{}", "上个月公司已付多少", workspace(true));
        AgentSkillExecution supplierPayments = new SupplierPayableSummarySkill(service, new ObjectMapper())
                .execute("{}", "上个月向供应商支付了多少钱", workspace(true));

        assertTrue(receipts.getAnswer().contains("1234.56"));
        assertFalse(receipts.getAnswer().contains("7000.00"));
        assertTrue(payments.getAnswer().contains("2345.67"));
        assertFalse(payments.getAnswer().contains("5200.00"));
        assertTrue(supplierPayments.getAnswer().contains("2345.67"));
        assertFalse(supplierPayments.getAnswer().contains("5200.00"));
        verify(service, times(2)).generate(any(), any(),
                eq(FinanceLedgerSnapshotService.LedgerDirection.BOTH));
        verify(service).generate(any(), any(),
                eq(FinanceLedgerSnapshotService.LedgerDirection.RENT_IN));

        @SuppressWarnings("unchecked")
        Map<String, Object> supplierCard = (Map<String, Object>) supplierPayments.getCards().get(0);
        assertEquals(YearMonth.from(LocalDate.now()).minusMonths(1).atDay(1).toString(), supplierCard.get("startDate"));
        assertEquals(YearMonth.from(LocalDate.now()).minusMonths(1).atEndOfMonth().toString(), supplierCard.get("endDate"));
        assertEquals("SUPPLIER_REGISTERED_PAYMENTS", supplierCard.get("requestedMetric"));
        assertEquals(new BigDecimal("2345.67"),
                ((DirectionSummary) supplierCard.get("summary")).getPeriodCash().getTotalRegistered());
    }

    @Test
    void coreFinancialWarningsStayAheadOfDynamicDataQualityWarnings() {
        FinanceLedgerSnapshotService service = mock(FinanceLedgerSnapshotService.class);
        Result result = result();
        result.setWarnings(Arrays.asList(
                "账期A到期日无效", "账期B到期日无效", "账期C到期日无效",
                "账期D到期日无效", "账期E到期日无效", "账期F到期日无效"));
        when(service.generate(any(), any(), any())).thenReturn(result);

        AgentSkillExecution enterprise = new EnterpriseKpiSkill(service, new ObjectMapper()).execute(
                "{\"asOfDate\":\"2026-08-31\"}", "公司现在应收应付", workspace(true));
        AgentSkillExecution supplier = new SupplierPayableSummarySkill(service, new ObjectMapper()).execute(
                "{\"asOfDate\":\"2026-08-31\",\"limit\":20}", "供应商应付多少", workspace(true));

        assertTrue(enterprise.getWarnings().get(0).contains("不代表利润"));
        assertTrue(enterprise.getWarnings().get(1).contains("当前台账快照"));
        assertTrue(enterprise.getWarnings().get(2).contains("不代表银行实际到账或出账"));
        assertTrue(enterprise.getWarnings().get(3).contains("SETTLEMENT_PERIOD_END"));
        assertTrue(enterprise.getWarnings().get(4).contains("未退押金"));
        assertTrue(enterprise.getWarnings().get(5).contains("到期日无效"));

        assertTrue(supplier.getWarnings().get(0).contains("不代表利润"));
        assertTrue(supplier.getWarnings().get(1).contains("当前台账快照"));
        assertTrue(supplier.getWarnings().get(2).contains("不代表银行实际出账"));
        assertTrue(supplier.getWarnings().get(3).contains("SETTLEMENT_PERIOD_END"));
        assertTrue(supplier.getWarnings().get(4).contains("不做跨项目同名供应商合并"));
        assertTrue(supplier.getWarnings().get(5).contains("到期日无效"));
        verify(service).generate(any(), any(), eq(FinanceLedgerSnapshotService.LedgerDirection.BOTH));
        verify(service).generate(any(), any(), eq(FinanceLedgerSnapshotService.LedgerDirection.RENT_IN));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enterpriseCardUsesOneGlobalLimitWithoutEmbeddingFullProjectLists() {
        FinanceLedgerSnapshotService service = mock(FinanceLedgerSnapshotService.class);
        Result result = result();
        result.getQuery().setLimit(2);
        result.getRentOut().setProjects(Arrays.asList(project("RO-A"), project("RO-B")));
        result.getRentOut().setProjectCount(2);
        result.getRentIn().setProjects(Arrays.asList(project("RI-A"), project("RI-B")));
        result.getRentIn().setProjectCount(2);
        when(service.generate(any(), any(), eq(FinanceLedgerSnapshotService.LedgerDirection.BOTH))).thenReturn(result);

        Map<String, Object> card = (Map<String, Object>) new EnterpriseKpiSkill(service, new ObjectMapper())
                .execute("{\"asOfDate\":\"2026-08-31\",\"limit\":2}", "公司财务总览", workspace(true))
                .getCards().get(0);

        assertEquals(4, card.get("totalCount"));
        assertEquals(2, card.get("displayedCount"));
        assertEquals(true, card.get("truncated"));
        assertEquals(2, ((java.util.List<?>) card.get("items")).size());
        assertEquals(Collections.emptyList(), ((DirectionSummary) card.get("rentOut")).getProjects());
        assertEquals(Collections.emptyList(), ((DirectionSummary) card.get("rentIn")).getProjects());
    }

    @Test
    @SuppressWarnings("unchecked")
    void supplierCardTruncatesRowsAndLabelsCurrentPartnerSource() {
        FinanceLedgerSnapshotService service = mock(FinanceLedgerSnapshotService.class);
        Result result = result();
        result.getQuery().setLimit(1);
        result.getRentIn().setProjects(Arrays.asList(project("RI-A"), project("RI-B")));
        result.getRentIn().setProjectCount(2);
        when(service.generate(any(), any(), eq(FinanceLedgerSnapshotService.LedgerDirection.RENT_IN))).thenReturn(result);

        AgentSkillExecution execution = new SupplierPayableSummarySkill(service, new ObjectMapper()).execute(
                "{\"asOfDate\":\"2026-08-31\",\"limit\":1}", "租入项目哪些应付到期？", workspace(true));

        Map<String, Object> card = (Map<String, Object>) execution.getCards().get(0);
        assertEquals("supplier-payable-summary", card.get("type"));
        assertEquals(2, card.get("totalCount"));
        assertEquals(1, card.get("displayedCount"));
        assertEquals(true, card.get("truncated"));
        assertEquals(1, card.get("limit"));
        assertEquals("CURRENT_PROJECT_PARTNER", card.get("supplierNameSource"));
        assertFalse(card.containsKey("rentOut"));
        assertFalse(card.containsKey("rentIn"));
        assertEquals(Collections.emptyList(), ((DirectionSummary) card.get("summary")).getProjects());
        assertTrue(execution.getAnswer().contains("登记实付"));
        assertTrue(execution.getAnswer().contains("ACTIVE本金核销"));
        assertTrue(execution.getEvidence().getApiList().contains("internal:settlement_payable_period"));
        assertTrue(execution.getEvidence().getApiList().contains("internal:supplier_payment"));
        assertTrue(execution.getEvidence().getApiList().contains("internal:project"));
        assertFalse(execution.getEvidence().getApiList().stream().anyMatch(value -> value.contains("customer")));
        assertFalse(execution.getEvidence().getApiList().stream().anyMatch(value -> value.contains("receivable")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void supplierCardKeepsSameNamedPartnersAsProjectRowsWithRegisteredCash() {
        FinanceLedgerSnapshotService service = mock(FinanceLedgerSnapshotService.class);
        Result result = result();
        ProjectSummary first = project("RI-A");
        first.setRegisteredCash(new BigDecimal("3000.00"));
        first.setPeriodRegisteredCash(new BigDecimal("1200.00"));
        ProjectSummary second = project("RI-B");
        second.setRegisteredCash(new BigDecimal("2200.00"));
        second.setPeriodRegisteredCash(new BigDecimal("1145.67"));
        result.getRentIn().setProjects(Arrays.asList(first, second));
        result.getRentIn().setProjectCount(2);
        when(service.generate(any(), any(), eq(FinanceLedgerSnapshotService.LedgerDirection.RENT_IN))).thenReturn(result);

        Map<String, Object> card = (Map<String, Object>) new SupplierPayableSummarySkill(service, new ObjectMapper())
                .execute("{\"asOfDate\":\"2026-08-31\",\"limit\":20}", "已经向各供应商支付了多少钱", workspace(true))
                .getCards().get(0);
        java.util.List<ProjectSummary> rows = (java.util.List<ProjectSummary>) card.get("items");

        assertEquals(Arrays.asList("RI-A", "RI-B"), rows.stream()
                .map(ProjectSummary::getProjectId).collect(java.util.stream.Collectors.toList()));
        assertEquals(Arrays.asList(new BigDecimal("3000.00"), new BigDecimal("2200.00")), rows.stream()
                .map(ProjectSummary::getRegisteredCash).collect(java.util.stream.Collectors.toList()));
        assertTrue(rows.stream().allMatch(row -> "供应商".equals(row.getCounterpartyName())));
    }

    @Test
    void financeGateRejectsBothSkills() {
        FinanceLedgerSnapshotService service = mock(FinanceLedgerSnapshotService.class);
        AgentRuntimeRecords.Workspace disabled = workspace(false);

        assertEquals("AGT403", assertThrows(MyBizException.class,
                () -> new EnterpriseKpiSkill(service, new ObjectMapper()).execute("{}", "财务总览", disabled)).getErrorCode());
        assertEquals("AGT403", assertThrows(MyBizException.class,
                () -> new SupplierPayableSummarySkill(service, new ObjectMapper()).execute("{}", "应付", disabled)).getErrorCode());
    }

    private Result result() {
        Query query = new Query();
        query.setAsOfDate("2026-08-31");
        query.setLimit(20);
        query.setRequestedMetric("FINANCE_LEDGER_OVERVIEW");
        Result result = new Result();
        result.setQuery(query);
        result.setRentOut(direction("RENT_OUT", "12000", "5500", "6500", "7000", "6000", "500"));
        result.setRentIn(direction("RENT_IN", "15000", "4500", "10500", "5200", "5000", "500"));
        result.setWarnings(Collections.singletonList("dueDateBasis=SETTLEMENT_PERIOD_END"));
        return result;
    }

    private DirectionSummary direction(String direction, String posted, String allocated, String outstanding,
                                       String cash, String eligible, String unallocated) {
        DirectionSummary summary = new DirectionSummary();
        summary.setBusinessDirection(direction);
        summary.setPostedPrincipal(new BigDecimal(posted));
        summary.setAllocatedPrincipalAsOf(new BigDecimal(allocated));
        summary.setOutstandingPrincipalAsOf(new BigDecimal(outstanding));
        summary.setDueAsOfOutstanding(BigDecimal.ZERO);
        summary.setOverdueOutstanding(BigDecimal.ZERO);
        summary.setDueTodayOutstanding(BigDecimal.ZERO);
        summary.setProjectCount(0);
        summary.setPeriodCount(0);
        summary.setProjects(Collections.emptyList());
        CashSummary cumulative = cash(cash, eligible, unallocated);
        CashSummary period = cash("0", "0", "0");
        summary.setCumulativeCash(cumulative);
        summary.setPeriodCash(period);
        return summary;
    }

    private CashSummary cash(String total, String eligible, String unallocated) {
        CashSummary cash = new CashSummary();
        cash.setTotalRegistered(new BigDecimal(total));
        cash.setPrincipalEligible(new BigDecimal(eligible));
        cash.setUnallocatedPrincipalAsOf(new BigDecimal(unallocated));
        cash.setRent(BigDecimal.ZERO);
        cash.setCompensation(BigDecimal.ZERO);
        cash.setDeposit(BigDecimal.ZERO);
        cash.setLateFee(BigDecimal.ZERO);
        cash.setOther(BigDecimal.ZERO);
        cash.setAllocatedPrincipalAsOf(BigDecimal.ZERO);
        return cash;
    }

    private ProjectSummary project(String id) {
        ProjectSummary item = new ProjectSummary();
        item.setProjectId(id);
        item.setProjectName(id + "项目");
        item.setCounterpartyName("供应商");
        item.setCounterpartyNameSource("CURRENT_PROJECT_PARTNER");
        item.setOutstandingPrincipalAsOf(BigDecimal.ONE);
        return item;
    }

    private AgentRuntimeRecords.Workspace workspace(boolean enabled) {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("cid-1");
        workspace.setSelectionMode("ALL");
        workspace.setProjectIds(Collections.emptyList());
        workspace.setFinanceEnabled(enabled);
        return workspace;
    }
}
