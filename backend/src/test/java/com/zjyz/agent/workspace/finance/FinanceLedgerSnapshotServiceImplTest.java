package com.zjyz.agent.workspace.finance;

import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Query;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Result;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.CustomerPaymentAllocationMapper;
import com.zjyz.dao.CustomerPaymentMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.SettlementPayablePeriodMapper;
import com.zjyz.dao.SettlementReceivablePeriodMapper;
import com.zjyz.dao.SupplierPaymentAllocationMapper;
import com.zjyz.dao.SupplierPaymentMapper;
import com.zjyz.pojo.entity.CustomerPaymentAllocationEntity;
import com.zjyz.pojo.entity.CustomerPaymentEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.SettlementPayablePeriodEntity;
import com.zjyz.pojo.entity.SettlementReceivablePeriodEntity;
import com.zjyz.pojo.entity.SupplierPaymentAllocationEntity;
import com.zjyz.pojo.entity.SupplierPaymentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FinanceLedgerSnapshotServiceImplTest {
    private SettlementReceivablePeriodMapper receivableMapper;
    private SettlementPayablePeriodMapper payableMapper;
    private CustomerPaymentMapper customerPaymentMapper;
    private SupplierPaymentMapper supplierPaymentMapper;
    private CustomerPaymentAllocationMapper customerAllocationMapper;
    private SupplierPaymentAllocationMapper supplierAllocationMapper;
    private ProjectMapper projectMapper;
    private FinanceLedgerSnapshotServiceImpl service;

    @BeforeEach
    void setUp() {
        receivableMapper = mock(SettlementReceivablePeriodMapper.class);
        payableMapper = mock(SettlementPayablePeriodMapper.class);
        customerPaymentMapper = mock(CustomerPaymentMapper.class);
        supplierPaymentMapper = mock(SupplierPaymentMapper.class);
        customerAllocationMapper = mock(CustomerPaymentAllocationMapper.class);
        supplierAllocationMapper = mock(SupplierPaymentAllocationMapper.class);
        projectMapper = mock(ProjectMapper.class);
        service = new FinanceLedgerSnapshotServiceImpl(receivableMapper, payableMapper,
                customerPaymentMapper, supplierPaymentMapper, customerAllocationMapper,
                supplierAllocationMapper, projectMapper,
                Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("Asia/Shanghai")));
        when(projectMapper.selectList(any())).thenReturn(Arrays.asList(
                project("RO-A", "rent_out", "客户甲"),
                project("RI-A", "rent_in", "供应商甲"),
                project("RI-B", "rent_in", "供应商乙")));
        when(receivableMapper.selectList(any())).thenReturn(Arrays.asList(
                receivable("R1", "cid-1", "RO-A", "10000", "2026-07-31", "ACTIVE", "2026-07-31T10:00:00"),
                receivable("R2", "cid-1", "RO-A", "2000", "2026-09-30", "OPEN", "2026-08-20T10:00:00"),
                receivable("R-VOID", "cid-1", "RO-A", "4444", "2026-07-01", "VOID", "2026-07-01T10:00:00"),
                receivable("R-X", "cid-x", "RO-A", "9999", "2026-07-01", "ACTIVE", "2026-07-01T10:00:00")));
        when(payableMapper.selectList(any())).thenReturn(Arrays.asList(
                payable("A1", "cid-1", "RI-A", "8000", "2026-07-31", "ACTIVE", "2026-07-31T10:00:00"),
                payable("A2", "cid-1", "RI-A", "2000", "2026-09-30", "OPEN", "2026-08-20T10:00:00"),
                payable("B1", "cid-1", "RI-B", "5000", "2026-08-31", "PARTIAL", "2026-08-01T10:00:00"),
                payable("P-VOID", "cid-1", "RI-A", "7777", "2026-07-01", "VOID", "2026-07-01T10:00:00")));
        when(customerPaymentMapper.selectList(any())).thenReturn(Arrays.asList(
                customerPayment("CP-RENT", "cid-1", "RO-A", "2026-08-15", "租金", "6000", 0),
                customerPayment("CP-DEPOSIT", "cid-1", "RO-A", "2026-08-16", "押金", "700", 0),
                customerPayment("CP-LATE", "cid-1", "RO-A", "2026-08-17", "滞纳金", "300", 0),
                customerPayment("CP-DELETED", "cid-1", "RO-A", "2026-08-18", "租金", "400", 1),
                customerPayment("CP-FUTURE", "cid-1", "RO-A", "2026-09-05", "租金", "1000", 0)));
        when(supplierPaymentMapper.selectList(any())).thenReturn(Arrays.asList(
                supplierPayment("SP-A", "cid-1", "RI-A", "2026-08-20", "租金", "3500", 0),
                supplierPayment("SP-LATE", "cid-1", "RI-A", "2026-08-21", "滞纳金", "200", 0),
                supplierPayment("SP-B", "cid-1", "RI-B", "2026-08-22", "赔偿", "1500", 0),
                supplierPayment("SP-FUTURE", "cid-1", "RI-A", "2026-09-05", "租金", "1000", 0),
                supplierPayment("SP-DELETED", "cid-1", "RI-A", "2026-08-22", "租金", "400", 1)));
        when(customerAllocationMapper.selectList(any())).thenReturn(Arrays.asList(
                customerAllocation("CA-1", "cid-1", "RO-A", "CP-RENT", "R1", "5500", "2026-08-15", "ACTIVE"),
                customerAllocation("CA-FUTURE", "cid-1", "RO-A", "CP-RENT", "R1", "1000", "2026-09-05", "ACTIVE"),
                customerAllocation("CA-OLD", "cid-1", "RO-A", "CP-RENT", "R1", "999", "2026-08-15", "SUPERSEDED")));
        when(supplierAllocationMapper.selectList(any())).thenReturn(Arrays.asList(
                supplierAllocation("SA-A", "cid-1", "RI-A", "SP-A", "A1", "3000", "2026-08-20", "ACTIVE"),
                supplierAllocation("SA-B", "cid-1", "RI-B", "SP-B", "B1", "1500", "2026-08-22", "ACTIVE"),
                supplierAllocation("SA-OLD", "cid-1", "RI-A", "SP-A", "A1", "999", "2026-08-20", "SUPERSEDED")));
    }

    @Test
    void separatesPostedAllocatedCashAndDueBucketsForAllScope() {
        Query query = query();
        Result result = service.generate(workspace("ALL"), query);

        money("12000.00", result.getRentOut().getPostedPrincipal());
        money("5500.00", result.getRentOut().getAllocatedPrincipalAsOf());
        money("6500.00", result.getRentOut().getOutstandingPrincipalAsOf());
        money("4500.00", result.getRentOut().getDueAsOfOutstanding());
        money("7000.00", result.getRentOut().getCumulativeCash().getTotalRegistered());
        money("6000.00", result.getRentOut().getCumulativeCash().getPrincipalEligible());
        money("500.00", result.getRentOut().getCumulativeCash().getUnallocatedPrincipalAsOf());
        money("700.00", result.getRentOut().getCumulativeCash().getDeposit());
        money("300.00", result.getRentOut().getCumulativeCash().getLateFee());
        money("6000.00", result.getRentOut().getPeriodCash().getRent());

        money("15000.00", result.getRentIn().getPostedPrincipal());
        money("4500.00", result.getRentIn().getAllocatedPrincipalAsOf());
        money("10500.00", result.getRentIn().getOutstandingPrincipalAsOf());
        money("8500.00", result.getRentIn().getDueAsOfOutstanding());
        money("5000.00", result.getRentIn().getOverdueOutstanding());
        money("3500.00", result.getRentIn().getDueTodayOutstanding());
        money("5200.00", result.getRentIn().getCumulativeCash().getTotalRegistered());
        money("5000.00", result.getRentIn().getCumulativeCash().getPrincipalEligible());
        money("500.00", result.getRentIn().getCumulativeCash().getUnallocatedPrincipalAsOf());
        money("200.00", result.getRentIn().getCumulativeCash().getLateFee());
        assertEquals("CURRENT_PROJECT_PARTNER", result.getRentIn().getProjects().get(0).getCounterpartyNameSource());
        assertTrue(result.getWarnings().stream().anyMatch(value -> value.contains("SETTLEMENT_PERIOD_END")));
        assertTrue(result.getWarnings().stream().anyMatch(value -> value.contains("当前台账快照")));
        assertTrue(result.getWarnings().stream().anyMatch(value -> value.contains("payment_date")));
    }

    @Test
    void explicitScopeDefensivelyExcludesOtherProjectsAndTenants() {
        Result result = service.generate(workspace("RI-A"), query());

        money("0.00", result.getRentOut().getPostedPrincipal());
        money("10000.00", result.getRentIn().getPostedPrincipal());
        money("3000.00", result.getRentIn().getAllocatedPrincipalAsOf());
        money("7000.00", result.getRentIn().getOutstandingPrincipalAsOf());
        money("3700.00", result.getRentIn().getCumulativeCash().getTotalRegistered());
        assertEquals(1, result.getRentIn().getProjectCount());
        assertEquals("RI-A", result.getRentIn().getProjects().get(0).getProjectId());
    }

    @Test
    void excludesVoidFutureDeletedSupersededCrossTenantAndOutOfScopeRows() {
        Result all = service.generate(workspace("ALL"), query());
        Result explicit = service.generate(workspace("RI-A"), query());

        // These exact totals would be larger if any sentinel row from the fixture leaked in.
        money("12000.00", all.getRentOut().getPostedPrincipal()); // excludes R-VOID 4444 and cross-cid R-X 9999
        money("5500.00", all.getRentOut().getAllocatedPrincipalAsOf()); // excludes future 1000 and SUPERSEDED 999
        money("7000.00", all.getRentOut().getCumulativeCash().getTotalRegistered()); // excludes deleted 400 and future 1000
        money("15000.00", all.getRentIn().getPostedPrincipal()); // excludes P-VOID 7777
        money("4500.00", all.getRentIn().getAllocatedPrincipalAsOf()); // excludes SUPERSEDED 999
        money("5200.00", all.getRentIn().getCumulativeCash().getTotalRegistered()); // excludes deleted 400 and future 1000
        money("10000.00", explicit.getRentIn().getPostedPrincipal()); // excludes out-of-scope RI-B 5000
        money("3700.00", explicit.getRentIn().getCumulativeCash().getTotalRegistered()); // excludes out-of-scope RI-B 1500
    }

    @Test
    void rentOutOrphanProjectDoesNotBlockRentInSnapshot() {
        when(receivableMapper.selectList(any())).thenReturn(Collections.singletonList(
                receivable("R-ORPHAN", "cid-1", "RO-ORPHAN", "999", "2026-07-31", "ACTIVE",
                        "2026-07-31T10:00:00")));

        Result result = service.generate(workspace("ALL"), query(),
                FinanceLedgerSnapshotService.LedgerDirection.RENT_IN);

        assertRentInFixture(result);
        verifyNoInteractions(receivableMapper, customerPaymentMapper, customerAllocationMapper);
        assertEquals("FIN409", assertThrows(MyBizException.class,
                () -> service.generate(workspace("ALL"), query())).getErrorCode());
    }

    @Test
    void orphanCustomerPaymentDoesNotBlockRentInSnapshot() {
        when(customerPaymentMapper.selectList(any())).thenReturn(Collections.singletonList(
                customerPayment("CP-ORPHAN", "cid-1", "RO-ORPHAN", "2026-08-15", "租金", "999", 0)));

        Result result = service.generate(workspace("ALL"), query(),
                FinanceLedgerSnapshotService.LedgerDirection.RENT_IN);

        assertRentInFixture(result);
        verifyNoInteractions(receivableMapper, customerPaymentMapper, customerAllocationMapper);
    }

    @Test
    void customerOverAllocationDoesNotBlockRentInSnapshot() {
        when(customerAllocationMapper.selectList(any())).thenReturn(Collections.singletonList(
                customerAllocation("CA-BAD", "cid-1", "RO-A", "CP-RENT", "R1", "10001",
                        "2026-08-15", "ACTIVE")));

        Result result = service.generate(workspace("ALL"), query(),
                FinanceLedgerSnapshotService.LedgerDirection.RENT_IN);

        assertRentInFixture(result);
        verifyNoInteractions(receivableMapper, customerPaymentMapper, customerAllocationMapper);
    }

    @Test
    void rentOutCorruptionDoesNotBlockEmptyRentInSnapshot() {
        when(receivableMapper.selectList(any())).thenReturn(Collections.singletonList(
                receivable("R-ORPHAN", "cid-1", "RO-ORPHAN", "999", "2026-07-31", "ACTIVE",
                        "2026-07-31T10:00:00")));
        when(payableMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(supplierPaymentMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(supplierAllocationMapper.selectList(any())).thenReturn(Collections.emptyList());

        Result result = service.generate(workspace("ALL"), query(),
                FinanceLedgerSnapshotService.LedgerDirection.RENT_IN);

        money("0.00", result.getRentIn().getPostedPrincipal());
        money("0.00", result.getRentIn().getOutstandingPrincipalAsOf());
        assertEquals(0, result.getRentIn().getProjectCount());
        verifyNoInteractions(receivableMapper, customerPaymentMapper, customerAllocationMapper);
    }

    @Test
    void rentInCorruptionFailsRentInAndEnterpriseButDoesNotBlockRentOutSnapshot() {
        when(payableMapper.selectList(any())).thenReturn(Collections.singletonList(
                payable("P-ORPHAN", "cid-1", "RI-ORPHAN", "999", "2026-07-31", "ACTIVE",
                        "2026-07-31T10:00:00")));

        Result rentOut = service.generate(workspace("ALL"), query(),
                FinanceLedgerSnapshotService.LedgerDirection.RENT_OUT);

        money("12000.00", rentOut.getRentOut().getPostedPrincipal());
        money("6500.00", rentOut.getRentOut().getOutstandingPrincipalAsOf());
        verifyNoInteractions(payableMapper, supplierPaymentMapper, supplierAllocationMapper);
        assertEquals("FIN409", assertThrows(MyBizException.class, () -> service.generate(
                workspace("ALL"), query(), FinanceLedgerSnapshotService.LedgerDirection.RENT_IN)).getErrorCode());
        assertEquals("FIN409", assertThrows(MyBizException.class,
                () -> service.generate(workspace("ALL"), query())).getErrorCode());
    }

    @Test
    void supplierOverAllocationStillFailsRentInAndEnterpriseSnapshots() {
        when(supplierAllocationMapper.selectList(any())).thenReturn(Collections.singletonList(
                supplierAllocation("SA-BAD", "cid-1", "RI-A", "SP-A", "A1", "10001",
                        "2026-08-20", "ACTIVE")));

        assertEquals("FIN409", assertThrows(MyBizException.class, () -> service.generate(
                workspace("ALL"), query(), FinanceLedgerSnapshotService.LedgerDirection.RENT_IN)).getErrorCode());
        assertEquals("FIN409", assertThrows(MyBizException.class,
                () -> service.generate(workspace("ALL"), query())).getErrorCode());
    }

    @Test
    void failsClosedWhenActiveAllocationExceedsPrincipal() {
        when(customerAllocationMapper.selectList(any())).thenReturn(Collections.singletonList(
                customerAllocation("CA-BAD", "cid-1", "RO-A", "CP-RENT", "R1", "10001", "2026-08-15", "ACTIVE")));

        MyBizException error = assertThrows(MyBizException.class,
                () -> service.generate(workspace("ALL"), query()));

        assertEquals("FIN409", error.getErrorCode());
    }

    @Test
    void invalidDueDateKeepsPrincipalButWarnsAndOmitsDueBucket() {
        when(payableMapper.selectList(any())).thenReturn(Collections.singletonList(
                payable("A1", "cid-1", "RI-A", "8000", "not-a-date", "ACTIVE", "2026-07-31T10:00:00")));
        when(supplierAllocationMapper.selectList(any())).thenReturn(Collections.emptyList());

        Result result = service.generate(workspace("ALL"), query());

        money("8000.00", result.getRentIn().getPostedPrincipal());
        money("0.00", result.getRentIn().getDueAsOfOutstanding());
        assertTrue(result.getWarnings().stream().anyMatch(value -> value.contains("到期日无效")));
    }

    @Test
    void rejectsPastAndFutureBalanceDatesButAllowsPastCashPeriodAgainstTodaySnapshot() {
        Query past = query();
        past.setAsOfDate("2026-08-30");
        Query future = query();
        future.setAsOfDate("2026-09-01");

        assertEquals("FIN400", assertThrows(MyBizException.class,
                () -> service.generate(workspace("ALL"), past)).getErrorCode());
        assertEquals("FIN400", assertThrows(MyBizException.class,
                () -> service.generate(workspace("ALL"), future)).getErrorCode());

        Query currentBalanceWithPastCashPeriod = query();
        currentBalanceWithPastCashPeriod.setStartDate("2026-08-01");
        currentBalanceWithPastCashPeriod.setEndDate("2026-08-20");
        Result result = service.generate(workspace("ALL"), currentBalanceWithPastCashPeriod);
        money("12000.00", result.getRentOut().getPostedPrincipal());
        money("6000.00", result.getRentOut().getPeriodCash().getRent());
        money("700.00", result.getRentOut().getPeriodCash().getDeposit());
        money("300.00", result.getRentOut().getPeriodCash().getLateFee());
        money("3500.00", result.getRentIn().getPeriodCash().getRent());
        money("0.00", result.getRentIn().getPeriodCash().getLateFee());
    }

    private Query query() {
        Query query = new Query();
        query.setAsOfDate("2026-08-31");
        query.setStartDate("2026-08-01");
        query.setEndDate("2026-08-31");
        query.setLimit(20);
        return query;
    }

    private void assertRentInFixture(Result result) {
        money("15000.00", result.getRentIn().getPostedPrincipal());
        money("4500.00", result.getRentIn().getAllocatedPrincipalAsOf());
        money("10500.00", result.getRentIn().getOutstandingPrincipalAsOf());
        money("5200.00", result.getRentIn().getCumulativeCash().getTotalRegistered());
        money("0.00", result.getRentOut().getPostedPrincipal());
        assertEquals(0, result.getRentOut().getProjectCount());
    }

    private AgentRuntimeRecords.Workspace workspace(String value) {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("cid-1");
        workspace.setFinanceEnabled(true);
        if ("ALL".equals(value)) {
            workspace.setSelectionMode("ALL");
            workspace.setProjectIds(Collections.emptyList());
        } else {
            workspace.setSelectionMode("EXPLICIT");
            workspace.setProjectId(value);
            workspace.setProjectIds(Collections.singletonList(value));
        }
        return workspace;
    }

    private ProjectEntity project(String id, String type, String partner) {
        ProjectEntity entity = new ProjectEntity();
        entity.setCid("cid-1");
        entity.setProjectId(id);
        entity.setProjectName(id + "项目");
        entity.setProjectBusinessType(type);
        if ("rent_in".equals(type)) entity.setPartnerName(partner); else entity.setTenantUnit(partner);
        return entity;
    }

    private SettlementReceivablePeriodEntity receivable(String id, String cid, String projectId,
                                                         String amount, String due, String status, String created) {
        SettlementReceivablePeriodEntity entity = new SettlementReceivablePeriodEntity();
        entity.setPeriodId(id); entity.setCid(cid); entity.setProjectId(projectId);
        entity.setPrincipalAmount(new BigDecimal(amount)); entity.setDueDate(due); entity.setStatus(status);
        entity.setCreateTime(LocalDateTime.parse(created));
        return entity;
    }

    private SettlementPayablePeriodEntity payable(String id, String cid, String projectId,
                                                   String amount, String due, String status, String created) {
        SettlementPayablePeriodEntity entity = new SettlementPayablePeriodEntity();
        entity.setPeriodId(id); entity.setCid(cid); entity.setProjectId(projectId);
        entity.setPrincipalAmount(new BigDecimal(amount)); entity.setDueDate(due); entity.setStatus(status);
        entity.setCreateTime(LocalDateTime.parse(created));
        return entity;
    }

    private CustomerPaymentEntity customerPayment(String id, String cid, String projectId,
                                                   String date, String type, String amount, int deleted) {
        CustomerPaymentEntity entity = new CustomerPaymentEntity();
        entity.setCustomerPaymentId(id); entity.setCid(cid); entity.setProjectId(projectId);
        entity.setPaymentDate(date); entity.setPaymentType(type); entity.setActualAmount(new BigDecimal(amount));
        entity.setIsDeleted(deleted);
        return entity;
    }

    private SupplierPaymentEntity supplierPayment(String id, String cid, String projectId,
                                                   String date, String type, String amount, int deleted) {
        SupplierPaymentEntity entity = new SupplierPaymentEntity();
        entity.setSupplierPaymentId(id); entity.setCid(cid); entity.setProjectId(projectId);
        entity.setPaymentDate(date); entity.setPaymentType(type); entity.setActualAmount(new BigDecimal(amount));
        entity.setIsDeleted(deleted);
        return entity;
    }

    private CustomerPaymentAllocationEntity customerAllocation(String id, String cid, String projectId,
                                                                 String paymentId, String periodId, String amount,
                                                                 String effectiveDate, String status) {
        CustomerPaymentAllocationEntity entity = new CustomerPaymentAllocationEntity();
        entity.setAllocationId(id); entity.setCid(cid); entity.setProjectId(projectId);
        entity.setCustomerPaymentId(paymentId); entity.setTargetType("SETTLEMENT_PRINCIPAL");
        entity.setTargetId(periodId); entity.setAllocationAmount(new BigDecimal(amount));
        entity.setEffectivePaymentDate(effectiveDate); entity.setAllocationStatus(status);
        return entity;
    }

    private SupplierPaymentAllocationEntity supplierAllocation(String id, String cid, String projectId,
                                                                 String paymentId, String periodId, String amount,
                                                                 String effectiveDate, String status) {
        SupplierPaymentAllocationEntity entity = new SupplierPaymentAllocationEntity();
        entity.setAllocationId(id); entity.setCid(cid); entity.setProjectId(projectId);
        entity.setSupplierPaymentId(paymentId); entity.setTargetType("SETTLEMENT_PRINCIPAL");
        entity.setTargetId(periodId); entity.setAllocationAmount(new BigDecimal(amount));
        entity.setEffectivePaymentDate(effectiveDate); entity.setAllocationStatus(status);
        return entity;
    }

    private void money(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
