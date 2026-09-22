package com.zjyz.agent.workspace.v2.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public and persistence models for the V2 rental-operations copilot.
 *
 * <p>Strings are intentionally used for lifecycle values at the database boundary so a newer
 * runtime can read older rows without failing enum deserialization.</p>
 */
public final class AgentV2Models {
    public static final String RUNTIME_VERSION = "V2";

    private AgentV2Models() {
    }

    @Data
    public static class CreateTurnRequest {
        private String message;
        private String clientRequestId;
        private ScopeSelection scopeSelection;
        /** Legacy preview compatibility; the canonical request uses scopeSelection. */
        private String selectionMode;
        /** Legacy preview compatibility; the canonical request uses scopeSelection. */
        private List<String> projectIds = Collections.emptyList();
        private Map<String, Object> context = Collections.emptyMap();
        private List<String> attachmentIds = Collections.emptyList();
        private String timezone;
    }

    @Data
    public static class ScopeSelection {
        private String selectionMode;
        private List<String> projectIds = Collections.emptyList();
        private Boolean explicitOverride;
    }

    @Data
    public static class AnswerInteractionRequest {
        private String message;
        private Map<String, Object> answer = Collections.emptyMap();
        private String clientRequestId;
        private Map<String, Object> context = Collections.emptyMap();
    }

    @Data
    public static class ScopeSnapshot {
        private String scopeType;
        private String requestedSelectionMode;
        private String effectiveSelectionMode;
        private List<String> projectIds = Collections.emptyList();
        private String candidateRequestedSelectionMode;
        private List<String> candidateProjectIds = Collections.emptyList();
        private String candidateScopeHash;
        private String inheritedRequestedSelectionMode;
        private List<String> inheritedProjectIds = Collections.emptyList();
        private String inheritedScopeHash;
        private boolean explicitOverride;
        private String contextTaskId;
        private int projectCount;
        private boolean frozen;
        private String scopeHash;
        private LocalDateTime frozenAt;
    }

    @Data
    public static class ContextMessage {
        private String role;
        private String content;
        private LocalDateTime createdAt;
    }

    @Data
    public static class BoundedContext {
        private String executionDate;
        private List<Map<String,Object>> attachmentRefs = new ArrayList<>();
        private List<Map<String, Object>> learningContext = new ArrayList<>();
        private String threadSummary;
        private Map<String, Object> previousTask;
        private List<ContextMessage> messages = new ArrayList<>();
        private Map<String, Object> uiContext = Collections.emptyMap();
        private int maxCharacters;
        private int usedCharacters;
        private boolean truncated;
    }

    @Data
    public static class ConversationInterpretation {
        private String dialogueAct;
        private String relationType;
        private List<com.zjyz.agent.workspace.learning.AgentLearningModels.Action> learningActions = new ArrayList<>();
        private List<String> selectedMemoryIds = new ArrayList<>();
        private boolean resumeOriginalTask;
        private double confidence;
        private String rationale;
        private TaskSpec taskSpec;
    }

    @Data
    public static class TaskSpec {
        private String schemaVersion = "2026-09-19.1";
        private List<String> taskKinds = new ArrayList<>();
        private String goal;
        private String resolvedGoal;
        private String expectedOutcome;
        /** Analysis object and metrics are interpreted independently of ranking/order and tool hints. */
        private String analysisTarget;
        private List<String> requestedMetrics = new ArrayList<>();
        private String timeRangeExpression;
        private String scopeIntent;
        private String riskLevel;
        private List<String> referencedEntities = new ArrayList<>();
        private List<String> capabilityHints = new ArrayList<>();
        private List<TaskRequirement> requirements = new ArrayList<>();
        private List<String> missingInputs = new ArrayList<>();
        private List<String> assumptions = new ArrayList<>();
    }

    @Data
    public static class TaskRequirement {
        private String requirementId;
        private String metricId;
        private String timeBasis;
        private Boolean requireComplete;
        private String startDate;
        private String endDate;
        private String asOfDate;
        private List<String> projectIds = new ArrayList<>();
        private String description;
        private List<String> capabilityCodes = new ArrayList<>();
        private Map<String,Object> criteria = new LinkedHashMap<>();
    }

    @Data
    public static class InterpretationResult {
        private boolean success;
        private String warning;
        private String errorCode;
        private List<Map<String, Object>> diagnostics = new ArrayList<>();
        private String provider;
        private String model;
        private int promptTokens;
        private int completionTokens;
        private ConversationInterpretation interpretation;
    }

    @Data
    public static class Capability {
        private String code;
        private String name;
        private String description;
        private String riskLevel;
        private String requiredScope;
        private String dataScope;
        private String scopeBehavior;
        private List<String> requiredInputs = new ArrayList<>();
        private boolean enabled;
        private boolean available;
        private String unavailableReason;
        private boolean readOnly;
        private List<String> examples = new ArrayList<>();
    }

    @Data
    public static class Task {
        private String taskId;
        private String turnId;
        private String parentTaskId;
        private String threadId;
        private String workspaceId;
        private String latestRunId;
        private String cid;
        private String ownerUid;
        private String clientRequestId;
        private String requestFingerprint;
        private String status;
        private String dialogueAct;
        private String relationType;
        private String goal;
        private String interpretationJson;
        private String taskSpecJson;
        private String scopeJson;
        private String contextJson;
        private String capabilitySnapshotJson;
        private Integer version;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime completedAt;
    }

    @Data
    public static class Interaction {
        private String interactionId;
        private String taskId;
        private String threadId;
        private String cid;
        private String ownerUid;
        private String interactionType;
        private String status;
        private String promptText;
        private String missingFieldsJson;
        private String optionsJson;
        private String answerJson;
        private String answerClientRequestId;
        private String answeredTaskId;
        private LocalDateTime createdAt;
        private LocalDateTime answeredAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class RunState {
        private String runId;
        private String taskId;
        private String turnId;
        private String runtimeVersion;
        private String status;
        private String outcomeJson;
        private String errorCode;
        private String errorMessage;
        private LocalDateTime completedAt;
    }
}
