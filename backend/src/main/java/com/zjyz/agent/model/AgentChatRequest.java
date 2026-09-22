package com.zjyz.agent.model;

import lombok.Data;

import java.util.Map;

@Data
public class AgentChatRequest {
    private String sessionId;
    private String message;
    private Map<String, Object> context;
}
