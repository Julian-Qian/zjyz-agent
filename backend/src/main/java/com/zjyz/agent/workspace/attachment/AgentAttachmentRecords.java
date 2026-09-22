package com.zjyz.agent.workspace.attachment;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public final class AgentAttachmentRecords {
    private AgentAttachmentRecords() {}
    @Data public static class Attachment {
        private String attachmentId, cid, ownerUid, threadId, clientRequestId, checksum;
        private String filename, mimeType, objectKey, status;
        private long byteSize;
        private int version, parseRevision, rowVersion;
        private LocalDateTime createdAt, expiresAt;
    }
    @Data public static class ParseJob {
        private String jobId, attachmentId, status, leaseToken, resultJson, errorCode;
        private int revision, attempts;
        private LocalDateTime updatedAt;
    }
    @Data public static class Block {
        private String id, type, text, location;
        private Integer page;
    }
    @Data public static class ParsedDocument {
        private String parserVersion = "attachment-v1";
        private String status = "READY";
        private int totalPages, parsedPages;
        private List<Block> blocks = new ArrayList<>();
        private List<String> gaps = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        public boolean complete() { return gaps.isEmpty(); }
        public void gap(String gap) { gaps.add(gap); status = "PARTIAL"; }
    }
}
