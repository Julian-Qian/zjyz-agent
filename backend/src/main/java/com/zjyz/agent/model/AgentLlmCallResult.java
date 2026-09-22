package com.zjyz.agent.model;

import lombok.Data;

@Data
public class AgentLlmCallResult {
    private boolean success;
    private String provider;
    private String model;
    private String answer;
    private Double confidence;
    private Integer promptTokens;
    private Integer completionTokens;
    private String warning;
}
