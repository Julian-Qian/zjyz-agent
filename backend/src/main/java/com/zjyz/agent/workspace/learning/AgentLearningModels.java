package com.zjyz.agent.workspace.learning;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.*;

public final class AgentLearningModels {
    private AgentLearningModels() {}
    @Data public static class Entry {
        private String id, cid, ownerUid, scopeType, projectId, kind, status, sourceId, content;
        private String requiredProjects;
        private String originThreadId, originMessageId, supersedesId, reason, evidenceChunkId;
        private Integer evidenceVersion, version;
        private LocalDateTime createdAt, updatedAt;
    }
    @Data public static class Action {
        private String action = "SAVE";
        private String kind = "BUSINESS_RULE";
        private String content, targetId, evidenceChunkId, sourceQuote, capabilityCode;
        private Integer expectedVersion;
    }
    @Data public static class Mutation {
        private String content, kind, scopeType, projectId, clientRequestId, reason;
        private Integer expectedVersion;
    }
    @Data public static class Verification {
        private String status = "PENDING_VERIFICATION";
        private String reason = "已记录，尚未找到可核验的现行规则。";
        private String evidenceChunkId;
        private Integer evidenceVersion;
    }
}
