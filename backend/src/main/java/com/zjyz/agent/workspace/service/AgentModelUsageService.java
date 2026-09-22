package com.zjyz.agent.workspace.service;

import com.zjyz.agent.model.AgentQuotaDecision;
import com.zjyz.agent.service.AgentQuotaService;
import com.zjyz.common.exception.MyBizException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class AgentModelUsageService {
    private final AgentQuotaService quotaService;
    private final AgentModelPricingService pricingService;

    public AgentModelUsageService(AgentQuotaService quotaService, AgentModelPricingService pricingService) {
        this.quotaService = quotaService;
        this.pricingService = pricingService;
    }

    public void requireBeforeCall(String userKey, String operation) {
        AgentQuotaDecision decision = quotaService.preCheckModelCall(userKey);
        if (decision == null || !decision.isAllow()) {
            throw new MyBizException(decision == null ? "AI额度检查失败" : decision.getReason(), "AGT429");
        }
    }

    public String currentUserKey() {
        return quotaService.resolveCurrentUserKey();
    }

    public BigDecimal recordCall(String userKey, String provider, String model,
                                 int promptTokens, int completionTokens, BigDecimal accumulated) {
        BigDecimal cost = pricingService.estimate(provider, model, promptTokens, completionTokens);
        quotaService.recordModelUsage(userKey, Math.max(0, promptTokens), Math.max(0, completionTokens),
                cost.doubleValue());
        return (accumulated == null ? BigDecimal.ZERO : accumulated).add(cost);
    }
}
