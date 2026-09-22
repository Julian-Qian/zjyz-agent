package com.zjyz.agent.workspace.context;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class AgentTaskFrame {
    private String taskType;
    private String domain;
    private String userGoal;
    private String timeRange;
    private String selectionMode;
    private List<String> projectIds = new ArrayList<>();
    private AgentTaskCoverage coverage = AgentTaskCoverage.NON_BUSINESS;
    private boolean followUp;
    private boolean requiresBusinessData;
    private List<String> minimumRequiredTools = new ArrayList<>();
    private String bindingVersion;

    public Map<String, Object> toAuditMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskType", taskType);
        result.put("domain", domain);
        result.put("userGoal", userGoal);
        result.put("timeRange", timeRange);
        result.put("selectionMode", selectionMode);
        result.put("projectIds", projectIds);
        result.put("coverage", coverage == null ? null : coverage.name());
        result.put("followUp", followUp);
        result.put("requiresBusinessData", requiresBusinessData);
        result.put("minimumRequiredTools", minimumRequiredTools);
        result.put("bindingVersion", bindingVersion);
        return result;
    }

    public String toPrompt() {
        return "taskType=" + safe(taskType)
                + ", domain=" + safe(domain)
                + ", timeRange=" + safe(timeRange)
                + ", selectionMode=" + safe(selectionMode)
                + ", projectIds=" + projectIds
                + ", coverage=" + coverage
                + ", followUp=" + followUp
                + ", requiresBusinessData=" + requiresBusinessData
                + ", minimumRequiredTools=" + minimumRequiredTools
                + ", userGoal=" + safe(userGoal);
    }

    /** Compatibility accessor for existing callers while the audit contract uses minimumRequiredTools. */
    public List<String> getRequiredToolCodes() {
        return minimumRequiredTools;
    }

    public void setRequiredToolCodes(List<String> requiredToolCodes) {
        this.minimumRequiredTools = requiredToolCodes == null ? new ArrayList<>() : requiredToolCodes;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
