package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds an immutable, bounded context snapshot before a Run is queued. */
@Service
public class AgentV2ContextBuilder {
    private final ObjectMapper objectMapper;

    @Value("${agent.v2.context.maxCharacters:24000}")
    private int maxCharacters;
    @Value("${agent.v2.context.maxMessages:16}")
    private int maxMessages;
    @Value("${agent.v2.context.maxMessageCharacters:6000}")
    private int maxMessageCharacters;

    public AgentV2ContextBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentV2Models.BoundedContext build(AgentRuntimeRecords.AgentThread thread,
                                               List<AgentRuntimeRecords.Message> recentDescending,
                                               AgentV2Models.Task previousTask,
                                               String currentMessage,
                                               Map<String, Object> uiContext) {
        int budget = Math.max(maxCharacters, 8000);
        AgentV2Models.BoundedContext result = new AgentV2Models.BoundedContext();
        result.setMaxCharacters(budget);
        result.setThreadSummary(truncate(thread == null ? null : thread.getSummary(), 4000));
        result.setPreviousTask(previousTaskView(previousTask));
        result.setUiContext(safeUiContext(uiContext, 4000));

        String current = truncate(currentMessage,
                Math.min(Math.max(maxMessageCharacters, 1000), budget));
        int reservedForCurrent = length(current);
        int used = length(result.getThreadSummary()) + jsonLength(result.getPreviousTask())
                + jsonLength(result.getUiContext());
        int historyBudget = Math.max(budget - reservedForCurrent, 0);
        if (used > historyBudget) {
            result.setUiContext(Collections.emptyMap());
            used = length(result.getThreadSummary()) + jsonLength(result.getPreviousTask());
        }
        if (used > historyBudget) {
            result.setThreadSummary(null);
            used = jsonLength(result.getPreviousTask());
        }
        if (used > historyBudget) {
            result.setPreviousTask(null);
            used = 0;
        }
        List<AgentRuntimeRecords.Message> chronological = new ArrayList<>();
        if (recentDescending != null) {
            chronological.addAll(recentDescending);
            Collections.reverse(chronological);
        }
        int start = Math.max(0, chronological.size() - Math.max(maxMessages - 1, 1));
        List<AgentV2Models.ContextMessage> selected = new ArrayList<>();
        boolean truncated = start > 0;
        for (int index = start; index < chronological.size(); index++) {
            AgentRuntimeRecords.Message source = chronological.get(index);
            if (source == null || (!"user".equals(source.getRole()) && !"assistant".equals(source.getRole()))) {
                continue;
            }
            String content = truncate(source.getContent(), Math.max(maxMessageCharacters, 1000));
            if (used + length(content) > historyBudget) {
                truncated = true;
                while (!selected.isEmpty() && used + length(content) > historyBudget) {
                    AgentV2Models.ContextMessage removed = selected.remove(0);
                    used -= length(removed.getContent());
                }
            }
            if (used + length(content) <= historyBudget) {
                selected.add(contextMessage(source.getRole(), content, source.getCreatedAt()));
                used += length(content);
            }
        }

        if (!selected.isEmpty()) {
            AgentV2Models.ContextMessage latest = selected.get(selected.size() - 1);
            if ("user".equals(latest.getRole()) && java.util.Objects.equals(current, latest.getContent())) {
                result.setMessages(selected);
                result.setUsedCharacters(used);
                result.setTruncated(truncated || length(currentMessage) > length(current));
                return result;
            }
        }
        while (!selected.isEmpty() && used + length(current) > budget) {
            AgentV2Models.ContextMessage removed = selected.remove(0);
            used -= length(removed.getContent());
            truncated = true;
        }
        selected.add(contextMessage("user", current, null));
        used += length(current);
        result.setMessages(selected);
        result.setUsedCharacters(used);
        result.setTruncated(truncated || length(currentMessage) > length(current));
        return result;
    }

    private Map<String, Object> previousTaskView(AgentV2Models.Task task) {
        if (task == null) {
            return null;
        }
        Map<String, Object> taskSpec = parseMap(task.getTaskSpecJson());
        Map<String, Object> persistedScope = parseMap(task.getScopeJson());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", task.getTaskId());
        Map<String,Object> previousContext=parseMap(task.getContextJson());
        if(previousContext!=null && previousContext.get("attachmentRefs") instanceof List)
            result.put("attachmentRefs",previousContext.get("attachmentRefs"));
        result.put("status", task.getStatus());
        if (taskSpec != null) {
            result.put("analysisTarget", taskSpec.get("analysisTarget"));
            result.put("requestedMetrics", taskSpec.get("requestedMetrics"));
            result.put("timeRangeExpression", taskSpec.get("timeRangeExpression"));
        }
        Object resolvedGoal = taskSpec == null ? null : taskSpec.get("resolvedGoal");
        result.put("resolvedGoal", resolvedGoal == null ? task.getGoal() : resolvedGoal);
        Map<String, Object> scopeSummary = new LinkedHashMap<>();
        if (persistedScope != null) {
            scopeSummary.put("scopeHash", persistedScope.get("scopeHash"));
            scopeSummary.put("projectCount", persistedScope.get("projectCount"));
            scopeSummary.put("requestedSelectionMode", persistedScope.get("requestedSelectionMode"));
            scopeSummary.put("effectiveSelectionMode", persistedScope.get("effectiveSelectionMode"));
        }
        result.put("scope", scopeSummary);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> safeUiContext(Map<String, Object> value, int max) {
        if (value == null || value.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() > max) {
                Map<String, Object> truncated = new LinkedHashMap<>();
                truncated.put("truncated", true);
                truncated.put("summary", json.substring(0, Math.max(1, max - 1)) + "…");
                return truncated;
            }
            return objectMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(value, Object.class);
            return parsed instanceof Map ? (Map<String, Object>) parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int jsonLength(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return objectMapper.writeValueAsString(value).length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private AgentV2Models.ContextMessage contextMessage(String role,
                                                        String content,
                                                        java.time.LocalDateTime createdAt) {
        AgentV2Models.ContextMessage message = new AgentV2Models.ContextMessage();
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        return message;
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private String truncate(String value, int max) {
        if (!StringUtils.hasText(value) || value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(1, max - 1)) + "…";
    }
}
