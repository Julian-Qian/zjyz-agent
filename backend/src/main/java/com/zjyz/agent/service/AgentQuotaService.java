package com.zjyz.agent.service;

import com.zjyz.agent.model.AgentQuotaDecision;
import com.zjyz.agent.model.AgentQuotaInfo;

public interface AgentQuotaService {
    String resolveCurrentUserKey();

    AgentQuotaDecision preCheck(String userKey, String message, boolean premiumRequested);

    AgentQuotaDecision consumeTenantTask(String userKey);

    void releaseTenantTask(String cid, String monthKey);

    AgentQuotaDecision preCheckModelCall(String userKey);

    void recordUsage(String userKey, int promptTokens, int completionTokens, boolean premiumUsed, double estimatedCostCny);

    void recordModelUsage(String userKey, int promptTokens, int completionTokens, double estimatedCostCny);

    AgentQuotaInfo getQuota(String userKey);

    int getRequestMaxOutputTokens();

    double estimateCostCny(String modelName, int promptTokens, int completionTokens);
}
