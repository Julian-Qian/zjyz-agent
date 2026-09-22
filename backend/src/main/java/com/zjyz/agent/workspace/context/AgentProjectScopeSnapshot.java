package com.zjyz.agent.workspace.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, run-local copy of the project boundary selected when the run was created.
 * Tool arguments cannot widen this boundary, and structured tool results are checked
 * before they are exposed to the model or persisted as artifacts.
 */
public final class AgentProjectScopeSnapshot {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ANSWER_PROJECT_ID = Pattern.compile(
            "(?i)(?:project[_\\s]?id|项目ID)\\s*(?:[=:：]|为|是|\\s+)\\s*[\\\"']?([a-z0-9_-]{1,80})");
    private final String cid;
    private final String scopeType;
    private final String selectionMode;
    private final String projectId;
    private final String projectBusinessType;
    private final String workspaceId;
    private final String workspaceName;
    private final Boolean financeEnabled;
    private final List<String> projectIds;
    private final Set<String> allowedProjectIds;

    private AgentProjectScopeSnapshot(AgentRuntimeRecords.Workspace workspace,
                                      String selectionMode,
                                      List<String> projectIds) {
        this.cid = workspace.getCid();
        this.scopeType = workspace.getScopeType();
        this.selectionMode = selectionMode;
        this.projectId = workspace.getProjectId();
        this.projectBusinessType = workspace.getProjectBusinessType();
        this.workspaceId = workspace.getWorkspaceId();
        this.workspaceName = workspace.getName();
        this.financeEnabled = workspace.getFinanceEnabled();
        this.projectIds = Collections.unmodifiableList(new ArrayList<>(projectIds));
        this.allowedProjectIds = Collections.unmodifiableSet(new LinkedHashSet<>(projectIds));
    }

    public static AgentProjectScopeSnapshot capture(AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null || !StringUtils.hasText(workspace.getCid())) {
            throw new MyBizException("工作空间项目范围缺失", "AGT400");
        }
        String mode = "ALL".equalsIgnoreCase(workspace.getSelectionMode()) ? "ALL" : "EXPLICIT";
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (workspace.getProjectIds() != null) {
            workspace.getProjectIds().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(ids::add);
        }
        if (StringUtils.hasText(workspace.getProjectId())) {
            ids.add(workspace.getProjectId().trim());
        }
        if ("EXPLICIT".equals(mode) && ids.isEmpty()) {
            throw new MyBizException("项目范围为空", "AGT400");
        }
        if (!"TENANT".equalsIgnoreCase(workspace.getScopeType())) {
            if (!StringUtils.hasText(workspace.getProjectId()) || ids.size() != 1
                    || !ids.contains(workspace.getProjectId())) {
                throw new MyBizException("单项目工作空间范围不一致", "AGT403");
            }
            mode = "EXPLICIT";
        }
        return new AgentProjectScopeSnapshot(workspace, mode, new ArrayList<>(ids));
    }

    public AgentRuntimeRecords.Workspace toWorkspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setCid(cid);
        workspace.setScopeType(scopeType);
        workspace.setProjectId(projectId);
        workspace.setProjectBusinessType(projectBusinessType);
        workspace.setSelectionMode(selectionMode);
        workspace.setProjectIds(projectIds);
        workspace.setFinanceEnabled(financeEnabled);
        workspace.setName(workspaceName);
        return workspace;
    }

    public void validateExecution(AgentSkillExecution execution) {
        validateExecution(execution, true);
    }

    public void validateExecution(AgentSkillExecution execution, boolean validateAnswerText) {
        if (execution == null) {
            throw new MyBizException("工具没有返回可核验结果", "AGT502");
        }
        if (isAllProjects()) {
            return;
        }
        validateNode(execution.getCards(), "cards");
        validateEvidence(execution.getEvidence());
        if (validateAnswerText) {
            validateAnswer(execution.getAnswer());
        }
        validateArtifact(execution.getArtifactContentJson());
    }

    public Map<String, Object> toAuditMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("scopeType", scopeType);
        value.put("selectionMode", selectionMode);
        value.put("projectIds", projectIds);
        value.put("projectCount", projectIds.size());
        return value;
    }

    public String getCid() {
        return cid;
    }

    public String getScopeType() {
        return scopeType;
    }

    public String getSelectionMode() {
        return selectionMode;
    }

    public List<String> getProjectIds() {
        return projectIds;
    }

    public boolean isAllProjects() {
        return "ALL".equals(selectionMode);
    }

    private void validateNode(Object value, String path) {
        if (value == null) {
            return;
        }
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object child = entry.getValue();
                String normalizedKey = key.replace("_", "").toLowerCase(Locale.ROOT);
                if ("projectid".equals(normalizedKey)) {
                    validateProjectId(child, path + "." + key);
                } else if ("projectids".equals(normalizedKey)) {
                    validateProjectIds(child, path + "." + key);
                }
                validateNode(child, path + "." + key);
            }
            return;
        }
        if (value instanceof Iterable) {
            int index = 0;
            for (Object child : (Iterable<?>) value) {
                validateNode(child, path + "[" + index++ + "]");
            }
            return;
        }
        if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                validateNode(Array.get(value, index), path + "[" + index + "]");
            }
        }
    }

    private void validateProjectIds(Object value, String path) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection) {
            for (Object project : (Collection<?>) value) {
                validateProjectId(project, path);
            }
            return;
        }
        validateProjectId(value, path);
    }

    private void validateProjectId(Object value, String path) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return;
        }
        String candidate = String.valueOf(value).trim();
        if (!allowedProjectIds.contains(candidate)) {
            throw new MyBizException("工具结果包含当前运行范围外的项目，已阻止输出", "AGT_SCOPE_VIOLATION");
        }
    }

    private void validateEvidence(AgentEvidence evidence) {
        if (evidence != null) {
            validateProjectIds(evidence.getProjectIds(), "evidence.projectIds");
        }
    }

    private void validateAnswer(String answer) {
        if (!StringUtils.hasText(answer)) {
            return;
        }
        Matcher matcher = ANSWER_PROJECT_ID.matcher(answer);
        while (matcher.find()) {
            validateProjectId(matcher.group(1), "answer.projectId");
        }
    }

    private void validateArtifact(String artifactContentJson) {
        if (!StringUtils.hasText(artifactContentJson)) {
            return;
        }
        try {
            validateNode(JSON.readValue(artifactContentJson, Object.class), "artifactContentJson");
        } catch (MyBizException error) {
            throw error;
        } catch (Exception error) {
            throw new MyBizException("工具产物不是可校验的JSON，已阻止输出", "AGT_SCOPE_VIOLATION");
        }
    }
}
