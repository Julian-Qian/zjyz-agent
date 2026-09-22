package com.zjyz.agent.workspace.knowledge;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentKnowledgeEntry {
    private String id;
    private String title;
    private String type;
    private String domain;
    private String authority;
    private String status;
    private String effectiveDate;
    private String source;
    private String content;
    private String sourceId;
    private Integer sourceVersion;
    private String chunkId;
    private String retrievalMethod;
    private Double score;
    private List<String> keywords = new ArrayList<>();
    private List<String> toolHints = new ArrayList<>();
}
