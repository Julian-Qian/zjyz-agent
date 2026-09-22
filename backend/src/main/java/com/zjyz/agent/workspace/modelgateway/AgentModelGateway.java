package com.zjyz.agent.workspace.modelgateway;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface AgentModelGateway {
    boolean isAvailable();

    ModelResult complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools, String reasoningEffort);

    @Data
    class ModelResult {
        private boolean success;
        /** Transport attempt metadata, excluding prompt/content. Unknown usage is not zero cost. */
        private List<ModelAttempt> attempts = new ArrayList<>();
        private String provider;
        private String model;
        private String content;
        private String warning;
        private String errorCode;
        private String finishReason;
        private int promptTokens;
        private int completionTokens;
        private Map<String, Object> assistantMessage;
        private List<ToolCall> toolCalls = new ArrayList<>();
    }

    @Data
    class ModelAttempt {
        private String attemptId;
        private String provider;
        private String model;
        private int promptTokens;
        private int completionTokens;
        private String usageStatus;
        private String errorCode;
    }

    @Data
    class ToolCall {
        private String id;
        private String name;
        private String arguments;
    }
}
