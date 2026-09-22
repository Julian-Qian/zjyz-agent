package com.zjyz.agent.model;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class AgentQuotaInfo {
    private Integer userDailyUsed;
    private Integer userDailyLimit;
    private Integer userMonthlyUsed;
    private Integer userMonthlyLimit;
    private Integer userMonthlyRemaining;
    private Boolean userMonthlyCallUnlimited;

    private Integer tenantMonthlyTaskUsed;
    private Integer tenantMonthlyTaskLimit;
    private Integer tenantMonthlyTaskRemaining;

    private Integer userMonthlyPromptTokenUsed;
    private Integer userMonthlyPromptTokenLimit;
    private Boolean userMonthlyPromptTokenUnlimited;
    private Integer userMonthlyCompletionTokenUsed;
    private Integer userMonthlyCompletionTokenLimit;

    private BigDecimal tenantMonthlyBudgetCny;
    private BigDecimal tenantMonthlyUsedCny;
    private BigDecimal tenantBudgetRemainingCny;
}
