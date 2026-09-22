package com.zjyz.agent.workspace.finance;

import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Query;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Result;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.CustomerPaymentAllocationMapper;
import com.zjyz.dao.CustomerPaymentMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.SettlementReceivablePeriodMapper;
import com.zjyz.pojo.entity.CustomerPaymentAllocationEntity;
import com.zjyz.pojo.entity.CustomerPaymentEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.SettlementReceivablePeriodEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReceivableCollectionServiceImplTest {

    @Test
    void recomputesHistoricalBalanceAtCutoffAndSeparatesCarryover() {
        SettlementReceivablePeriodMapper periodMapper = mock(SettlementReceivablePeriodMapper.class);
        CustomerPaymentAllocationMapper allocationMapper = mock(CustomerPaymentAllocationMapper.class);
        CustomerPaymentMapper paymentMapper = mock(CustomerPaymentMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ReceivableCollectionServiceImpl service = new ReceivableCollectionServiceImpl(
                periodMapper, allocationMapper, paymentMapper, projectMapper);
        configure(service);

        SettlementReceivablePeriodEntity carryover = period("period-old", "2025-12-31", "150000");
        SettlementReceivablePeriodEntity current = period("period-current", "2026-07-01", "40000");
        when(periodMapper.selectList(any())).thenReturn(Arrays.asList(carryover, current));

        CustomerPaymentAllocationEntity effective = allocation("allocation-effective", "period-old", "50000", "2026-01-15");
        CustomerPaymentAllocationEntity future = allocation("allocation-future", "period-old", "10000", "2026-09-01");
        when(allocationMapper.selectList(any())).thenReturn(Arrays.asList(effective, future));

        ProjectEntity project = new ProjectEntity();
        project.setProjectId("project-1");
        project.setProjectName("测试项目");
        project.setTenantUnit("测试客户");
        project.setManagerName("张三");
        project.setProjectBusinessType("rent_out");
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project));

        CustomerPaymentEntity payment = new CustomerPaymentEntity();
        payment.setCustomerPaymentId("payment-1");
        payment.setProjectId("project-1");
        payment.setPaymentDate("2026-02-01");
        when(paymentMapper.selectList(any())).thenReturn(Collections.singletonList(payment));

        Query query = new Query();
        query.setAsOfDate("2026-08-22");
        query.setYear(2026);
        query.setScope("ALL_OVERDUE");
        Result result = service.generate("cid-1", query);

        assertEquals(1, result.getSummary().getProjectCount());
        assertMoney("140000.00", result.getSummary().getTotalOutstanding());
        assertMoney("40000.00", result.getSummary().getCurrentYearOutstanding());
        assertMoney("100000.00", result.getSummary().getCarryoverOutstanding());
        assertEquals("MIXED", result.getItems().get(0).getYearGroup());
        assertEquals("P1", result.getItems().get(0).getPriority());
        assertEquals("2026-02-01", result.getItems().get(0).getLatestPaymentDate());
        assertEquals(2, result.getItems().get(0).getPeriods().size());
    }

    @Test
    void rejectsFutureCutoffBeforeReadingFinanceTables() {
        SettlementReceivablePeriodMapper periodMapper = mock(SettlementReceivablePeriodMapper.class);
        ReceivableCollectionServiceImpl service = new ReceivableCollectionServiceImpl(
                periodMapper,
                mock(CustomerPaymentAllocationMapper.class),
                mock(CustomerPaymentMapper.class),
                mock(ProjectMapper.class));
        configure(service);
        Query query = new Query();
        query.setAsOfDate(LocalDate.now().plusDays(1).toString());

        MyBizException error = assertThrows(MyBizException.class, () -> service.generate("cid-1", query));

        assertEquals("FIN400", error.getErrorCode());
        verifyNoInteractions(periodMapper);
    }

    private void configure(ReceivableCollectionServiceImpl service) {
        ReflectionTestUtils.setField(service, "p1OverdueDays", 90);
        ReflectionTestUtils.setField(service, "p1OutstandingAmount", new BigDecimal("100000"));
        ReflectionTestUtils.setField(service, "p2OverdueDays", 30);
        ReflectionTestUtils.setField(service, "p2OutstandingAmount", new BigDecimal("50000"));
        ReflectionTestUtils.setField(service, "maxRows", 5000);
    }

    private SettlementReceivablePeriodEntity period(String id, String dueDate, String amount) {
        SettlementReceivablePeriodEntity entity = new SettlementReceivablePeriodEntity();
        entity.setPeriodId(id);
        entity.setCid("cid-1");
        entity.setProjectId("project-1");
        entity.setSettlementDocumentId("settlement-1");
        entity.setDueDate(dueDate);
        entity.setPrincipalAmount(new BigDecimal(amount));
        entity.setStatus("OPEN");
        return entity;
    }

    private CustomerPaymentAllocationEntity allocation(String id, String periodId, String amount, String effectiveDate) {
        CustomerPaymentAllocationEntity entity = new CustomerPaymentAllocationEntity();
        entity.setAllocationId(id);
        entity.setTargetId(periodId);
        entity.setAllocationAmount(new BigDecimal(amount));
        entity.setEffectivePaymentDate(effectiveDate);
        entity.setAllocationStatus("ACTIVE");
        return entity;
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
