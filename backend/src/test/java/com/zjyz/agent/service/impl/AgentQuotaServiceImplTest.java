package com.zjyz.agent.service.impl;

import com.zjyz.agent.dao.AgentTenantTaskUsageMapper;
import com.zjyz.agent.dao.AgentUsageRecordMapper;
import com.zjyz.agent.model.AgentQuotaDecision;
import com.zjyz.agent.model.AgentUsageRecordEntity;
import com.zjyz.common.security.AuthContext;
import com.zjyz.membership.domain.EntitlementKeys;
import com.zjyz.membership.service.MembershipEntitlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentQuotaServiceImplTest {
    private final AgentUsageRecordMapper usageMapper = mock(AgentUsageRecordMapper.class);
    private final AgentTenantTaskUsageMapper tenantTaskMapper = mock(AgentTenantTaskUsageMapper.class);
    private final MembershipEntitlementService entitlementService = mock(MembershipEntitlementService.class);
    private final AgentQuotaServiceImpl service = new AgentQuotaServiceImpl();
    private AgentUsageRecordEntity record;

    @BeforeEach
    void setUp() {
        AuthContext.set("u1", "c1");
        ReflectionTestUtils.setField(service, "usageRecordMapper", usageMapper);
        ReflectionTestUtils.setField(service, "tenantTaskUsageMapper", tenantTaskMapper);
        ReflectionTestUtils.setField(service, "membershipEntitlementService", entitlementService);
        ReflectionTestUtils.setField(service, "defaultUserMonthlyCallLimit", 120);
        ReflectionTestUtils.setField(service, "defaultUserDailyCallLimit", 20);
        ReflectionTestUtils.setField(service, "defaultTenantMonthlyTaskLimit", 300);
        ReflectionTestUtils.setField(service, "defaultUserMonthlyPromptTokenLimit", 250000);
        ReflectionTestUtils.setField(service, "defaultUserMonthlyCompletionTokenLimit", 80000);
        ReflectionTestUtils.setField(service, "defaultTenantMonthlyBudgetCny", 500d);
        ReflectionTestUtils.setField(service, "requestMaxInputChars", 1500);
        ReflectionTestUtils.setField(service, "requestMaxInputTokens", 4000);

        record = new AgentUsageRecordEntity();
        record.setUserKey("u1");
        record.setCid("c1");
        record.setDayKey(LocalDate.now().toString());
        record.setMonthKey(YearMonth.now().toString());
        record.setDailyCalls(0);
        record.setMonthlyCalls(0);
        record.setMonthlyPromptTokens(0);
        record.setMonthlyCompletionTokens(0);
        record.setMonthlyPremiumCalls(0);
        record.setMonthlyCostCny(BigDecimal.ZERO);
        when(usageMapper.selectByUserMonth("u1", "c1", YearMonth.now().toString())).thenReturn(record);
        when(usageMapper.sumTenantMonthlyCost("c1", YearMonth.now().toString())).thenReturn(0d);
        when(entitlementService.requirePositiveLimit(eq("c1"), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(1);
                    if (EntitlementKeys.AI_TENANT_MONTHLY_TASK_LIMIT.equals(key)) return 300L;
                    if (EntitlementKeys.AI_TENANT_MONTHLY_BUDGET_CENTS.equals(key)) return 50000L;
                    if (EntitlementKeys.AI_USER_MONTHLY_CALL_LIMIT.equals(key)) return 120L;
                    return invocation.getArgument(2);
                });
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void consumesExactlyOneSharedTenantTask() {
        when(tenantTaskMapper.incrementIfBelowLimit("c1", YearMonth.now().toString(), 300)).thenReturn(1);
        when(tenantTaskMapper.currentTaskCount("c1", YearMonth.now().toString())).thenReturn(1);

        AgentQuotaDecision decision = service.consumeTenantTask("u1");

        assertTrue(decision.isAllow());
        assertEquals(1, decision.getQuota().getTenantMonthlyTaskUsed());
        assertEquals(300, decision.getQuota().getTenantMonthlyTaskLimit());
        assertEquals(299, decision.getQuota().getTenantMonthlyTaskRemaining());
        verify(tenantTaskMapper).ensureMonth("c1", YearMonth.now().toString());
        verify(tenantTaskMapper).incrementIfBelowLimit("c1", YearMonth.now().toString(), 300);
        verify(usageMapper, never()).incrementUsage(eq("u1"), eq("c1"), anyString(), anyString(),
                eq(0), eq(0), eq(0), eq(0d));
    }

    @Test
    void rejectsWhenSharedTenantTaskQuotaIsExhausted() {
        when(tenantTaskMapper.incrementIfBelowLimit("c1", YearMonth.now().toString(), 300)).thenReturn(0);
        when(tenantTaskMapper.currentTaskCount("c1", YearMonth.now().toString())).thenReturn(300);

        AgentQuotaDecision decision = service.consumeTenantTask("u1");

        assertFalse(decision.isAllow());
        assertEquals("企业本月共享任务额度已用完", decision.getReason());
        assertEquals(0, decision.getQuota().getTenantMonthlyTaskRemaining());
    }

    @Test
    void allowsConfiguredUserBeyondMonthlyPromptTokenLimit() {
        record.setMonthlyPromptTokens(250000);
        when(usageMapper.selectMonthlyPromptTokenUnlimitedOverride("u1", "c1")).thenReturn(1);

        AgentQuotaDecision decision = service.preCheck("u1", "继续测试小云", false);

        assertTrue(decision.isAllow());
        assertTrue(decision.getQuota().getUserMonthlyPromptTokenUnlimited());
        assertNull(decision.getQuota().getUserMonthlyPromptTokenLimit());
    }

    @Test
    void keepsMonthlyPromptTokenLimitForUsersWithoutOverride() {
        record.setMonthlyPromptTokens(250000);

        AgentQuotaDecision decision = service.preCheck("u1", "继续测试小云", false);

        assertFalse(decision.isAllow());
        assertEquals("月输入token已达上限", decision.getReason());
        assertFalse(decision.getQuota().getUserMonthlyPromptTokenUnlimited());
        assertEquals(250000, decision.getQuota().getUserMonthlyPromptTokenLimit());
    }

    @Test
    void allowsConfiguredUserBeyondMonthlyCallLimit() {
        record.setMonthlyCalls(600);
        when(usageMapper.selectMonthlyCallUnlimitedOverride("u1", "c1")).thenReturn(1);

        AgentQuotaDecision decision = service.preCheck("u1", "继续测试小云", false);

        assertTrue(decision.isAllow());
        assertTrue(decision.getQuota().getUserMonthlyCallUnlimited());
        assertNull(decision.getQuota().getUserMonthlyLimit());
        assertNull(decision.getQuota().getUserMonthlyRemaining());
    }

    @Test
    void keepsMonthlyCallLimitForUsersWithoutOverride() {
        record.setMonthlyCalls(120);

        AgentQuotaDecision decision = service.preCheck("u1", "继续测试小云", false);

        assertFalse(decision.isAllow());
        assertEquals("月调用次数已达上限", decision.getReason());
        assertFalse(decision.getQuota().getUserMonthlyCallUnlimited());
        assertEquals(120, decision.getQuota().getUserMonthlyLimit());
        assertEquals(0, decision.getQuota().getUserMonthlyRemaining());
    }
}
