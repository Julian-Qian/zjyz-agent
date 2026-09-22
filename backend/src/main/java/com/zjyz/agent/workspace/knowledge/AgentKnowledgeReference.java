package com.zjyz.agent.workspace.knowledge;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AgentKnowledgeReference {
    private String id;
    private String title;
    private String type;
    private String domain;
    private String authority;
    private String effectiveDate;
    private String source;
    private String sourceId;
    private Integer sourceVersion;
    private String chunkId;
    private String retrievalMethod;
    private Double score;

    public static AgentKnowledgeReference from(AgentKnowledgeEntry entry) {
        AgentKnowledgeReference reference = new AgentKnowledgeReference();
        reference.setId(entry.getId());
        reference.setTitle(entry.getTitle());
        reference.setType(entry.getType());
        reference.setDomain(entry.getDomain());
        reference.setAuthority(entry.getAuthority());
        reference.setEffectiveDate(entry.getEffectiveDate());
        reference.setSource(entry.getSource());
        reference.setSourceId(entry.getSourceId());
        reference.setSourceVersion(entry.getSourceVersion());
        reference.setChunkId(entry.getChunkId());
        reference.setRetrievalMethod(entry.getRetrievalMethod());
        reference.setScore(entry.getScore());
        return reference;
    }

    public Map<String, Object> toAuditMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("title", title);
        result.put("type", type);
        result.put("domain", domain);
        result.put("authority", authority);
        result.put("effectiveDate", effectiveDate);
        result.put("source", source);
        if (sourceId != null) {
            result.put("sourceId", sourceId);
            result.put("sourceVersion", sourceVersion);
            result.put("chunkId", chunkId);
            result.put("retrievalMethod", retrievalMethod);
            result.put("score", score);
        }
        return result;
    }
}
