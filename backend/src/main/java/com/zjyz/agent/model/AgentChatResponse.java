package com.zjyz.agent.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AgentChatResponse {
    private String sessionId;
    private String answer;
    private String intent;
    private Double confidence;
    private String provider;
    private String model;
    private List<Map<String, Object>> cards;
    private AgentEvidence evidence;
    private List<String> warnings;
    private String traceId;
    private Boolean degradeMode;
    private AgentQuotaInfo quota;

    // 澄清交互：当参数不足时要求用户补参，避免直接“数据不足”。
    private Boolean needClarification;
    private String clarificationQuestion;
    private List<String> missingSlots;
}
