package com.zjyz.agent.workspace.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

public final class AgentRuntimeRecords {
    private AgentRuntimeRecords() {
    }

    @Data
    public static class Workspace {
        private String workspaceId;
        private String cid;
        private String scopeType;
        private String scopeKey;
        private String projectId;
        private String projectBusinessType;
        private String selectionMode;
        private List<String> projectIds = Collections.emptyList();
        private Boolean financeEnabled;
        private String name;
        private String status;
        private String createdBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class AgentThread {
        private String threadId;
        private String workspaceId;
        private String cid;
        private String ownerUid;
        private String title;
        private String status;
        private String summary;
        private Integer summaryVersion;
        private LocalDateTime lastMessageAt;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class Message {
        private String messageId;
        private String threadId;
        private String runId;
        private String cid;
        private String role;
        private String contentType;
        private String content;
        private String metadataJson;
        private Integer tokenCount;
        private LocalDateTime createdAt;
    }

    @Data
    public static class Run {
        private String runId;
        private String threadId;
        private String workspaceId;
        private String cid;
        private String ownerUid;
        private String clientRequestId;
        private String status;
        private String modelProvider;
        private String modelName;
        private String reasoningEffort;
        private Integer iterationCount;
        private Integer toolCallCount;
        private Integer promptTokens;
        private Integer completionTokens;
        private BigDecimal estimatedCostCny;
        private LocalDateTime startedAt;
        private LocalDateTime heartbeatAt;
        private LocalDateTime completedAt;
        private String errorCode;
        private String errorMessage;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class Step {
        private String stepId;
        private String runId;
        private Integer seqNo;
        private String stepType;
        private String status;
        private String summary;
        private String inputSummary;
        private String outputSummary;
        private String errorCode;
        private String errorMessage;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
    }

    @Data
    public static class ToolCall {
        private String toolCallId;
        private String runId;
        private String stepId;
        private String callRef;
        private String toolCode;
        private String toolVersion;
        private String riskLevel;
        private String argumentsJson;
        private String argumentsHash;
        private String status;
        private String resultJson;
        private String resultSummary;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private String errorCode;
    }

    @Data
    public static class Artifact {
        private String artifactId;
        private String threadId;
        private String runId;
        private String workspaceId;
        private String cid;
        private String projectId;
        private String artifactType;
        private String title;
        private String mimeType;
        private String storageType;
        private String objectKey;
        private String contentJson;
        private String checksum;
        private String status;
        private String createdBy;
        private LocalDateTime createdAt;
    }

    @Data
    public static class RunEvent {
        private String eventId;
        private String runId;
        private Integer seqNo;
        private String eventType;
        private String payloadJson;
        private Integer visibleToUser;
        private LocalDateTime createdAt;
    }
}
