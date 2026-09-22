package com.zjyz.agent.workspace.knowledge;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class AgentKnowledgeRecords {
    private AgentKnowledgeRecords() {
    }

    @Data
    public static class Source {
        private String sourceId;
        private String scopeType;
        private String cid;
        private String projectId;
        private String sourceType;
        private String externalRef;
        private String title;
        private String description;
        private String domainCode;
        private String tags;
        private String authorityCode;
        private Integer versionNo;
        private Integer activeVersion;
        private String lifecycleStatus;
        private String indexStatus;
        private String originalFileName;
        private String mimeType;
        private Long fileSize;
        private String objectKey;
        private String contentChecksum;
        private String parserVersion;
        private String embeddingModel;
        private Integer embeddingDimensions;
        private Integer chunkCount;
        private String errorCode;
        private String errorMessage;
        private String createdBy;
        private String updatedBy;
        private String publishedBy;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private LocalDateTime publishedAt;
        private LocalDateTime archivedAt;
    }

    @Data
    public static class Chunk {
        private String chunkId;
        private String sourceId;
        private Integer sourceVersion;
        private String cid;
        private Integer chunkSeq;
        private String headingPath;
        private String content;
        private String contentHash;
        private Integer tokenCount;
        private String qdrantCollection;
        private String qdrantPointId;
        private String status;
        private String metadataJson;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String sourceTitle;
        private String sourceTags;
        private String domainCode;
        private String authorityCode;
        private String sourceDescription;
        private String lifecycleStatus;
    }

    @Data
    public static class Job {
        private String jobId;
        private String sourceId;
        private Integer sourceVersion;
        private String cid;
        private String jobType;
        private String status;
        private Integer attemptCount;
        private LocalDateTime nextRetryAt;
        private String lockedBy;
        private LocalDateTime lockedAt;
        private String errorCode;
        private String errorMessage;
        private LocalDateTime createdAt;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class ReferenceAudit {
        private String runId;
        private String messageId;
        private String cid;
        private String sourceId;
        private Integer sourceVersion;
        private String chunkId;
        private String retrievalMethod;
        private Integer rankNo;
        private BigDecimal rawScore;
        private BigDecimal finalScore;
        private Integer cited;
        private LocalDateTime createdAt;
    }

    @Data
    public static class SearchHit {
        private String pointId;
        private String sourceId;
        private Integer sourceVersion;
        private String chunkId;
        private double score;
        private String retrievalMethod;
    }

    @Data
    public static class SearchPreview {
        private String mode;
        private boolean degraded;
        private String warning;
        private List<SearchPreviewItem> items = new ArrayList<>();
    }

    @Data
    public static class SearchPreviewItem {
        private String sourceId;
        private String chunkId;
        private String title;
        private String headingPath;
        private String excerpt;
        private String retrievalMethod;
        private double score;
    }
}
