package com.zjyz.agent.model;

import lombok.Data;

import java.util.List;

@Data
public class AgentEvidence {
    private String evidenceId;
    private String metricId;
    private String definitionVersion;
    private String timeBasis;
    private String completeness;
    private String availability;
    private String queriedAt;
    private List<String> grain;
    private List<String> sourceRefs;

    /** Canonical code from AgentToolCatalog; never a model-facing alias. */
    private String toolCode;
    private String scopeType;
    private String selectionMode;
    private List<String> projectIds;
    private String timeRange;
    private java.util.Map<String,Object> criteria;
    private List<String> skills;
    private List<String> apiList;
    private Integer recordCount;
}
