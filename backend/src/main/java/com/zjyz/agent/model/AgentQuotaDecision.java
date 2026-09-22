package com.zjyz.agent.model;

import lombok.Data;

@Data
public class AgentQuotaDecision {
    private boolean allow;
    private boolean degradeMode;
    private boolean premiumAllowed;
    private String reason;
    private int estimatedPromptTokens;
    private AgentQuotaInfo quota;
}
