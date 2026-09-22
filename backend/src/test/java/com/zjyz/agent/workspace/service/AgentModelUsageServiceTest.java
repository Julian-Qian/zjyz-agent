package com.zjyz.agent.workspace.service;

import com.zjyz.agent.model.AgentQuotaDecision;
import com.zjyz.agent.service.AgentQuotaService;
import com.zjyz.common.exception.MyBizException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentModelUsageServiceTest {
    @Test
    void accumulatesMultiRoundCostAndRecordsEachCall() {
        AgentQuotaService quota = mock(AgentQuotaService.class);
        AgentModelPricingService pricing = mock(AgentModelPricingService.class);
        when(pricing.estimate("glm", "m1", 100, 20)).thenReturn(new BigDecimal("0.01230000"));
        when(pricing.estimate("glm", "m1", 80, 10)).thenReturn(new BigDecimal("0.00770000"));
        AgentModelUsageService service = new AgentModelUsageService(quota, pricing);

        BigDecimal total = service.recordCall("u1", "glm", "m1", 100, 20, BigDecimal.ZERO);
        total = service.recordCall("u1", "glm", "m1", 80, 10, total);

        assertEquals(new BigDecimal("0.02000000"), total);
        verify(quota).recordModelUsage("u1", 100, 20, 0.0123d);
        verify(quota).recordModelUsage("u1", 80, 10, 0.0077d);
    }

    @Test
    void budgetRejectionStopsBeforeModelCall() {
        AgentQuotaService quota = mock(AgentQuotaService.class);
        AgentQuotaDecision denied = new AgentQuotaDecision();
        denied.setAllow(false);
        denied.setReason("租户月预算已达上限");
        when(quota.preCheckModelCall("u1")).thenReturn(denied);
        AgentModelUsageService service = new AgentModelUsageService(quota, mock(AgentModelPricingService.class));

        MyBizException error = assertThrows(MyBizException.class,
                () -> service.requireBeforeCall("u1", "round"));
        assertEquals("AGT429", error.getErrorCode());
    }
}
