package com.zjyz.agent.workspace.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.service.AgentQuotaService;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeManagementService;
import com.zjyz.agent.workspace.model.AgentWorkspaceRequests;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.common.security.AuthContext;
import com.zjyz.common.util.CommonUtil;
import com.zjyz.dao.FeatureAccountWhitelistMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.pojo.entity.FeatureAccountWhitelistEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentWorkspaceRuntimeService {
    public static final String FEATURE_CODE = "AGENT_WORKSPACE";
    public static final String FINANCE_COLLECTION_FEATURE_CODE = "AGENT_FINANCE_COLLECTION";
    public static final String SCOPE_PROJECT = "PROJECT";
    public static final String SCOPE_TENANT = "TENANT";
    public static final String SCOPE_UNIFIED = "UNIFIED";
    public static final String SELECTION_ALL = "ALL";
    public static final String SELECTION_EXPLICIT = "EXPLICIT";

    private final AgentRuntimeMapper mapper;
    private final ProjectMapper projectMapper;
    private final FeatureAccountWhitelistMapper whitelistMapper;
    private final AgentQuotaService quotaService;
    private final AgentRunEventService eventService;
    private final ObjectMapper objectMapper;
    private final AgentKnowledgeManagementService knowledgeManagementService;

    @Value("${agent.workspace.enabled:${AGENT_WORKSPACE_ENABLED:false}}")
    private boolean workspaceEnabled;
    @Value("${agent.workspace.whitelistRequired:true}")
    private boolean whitelistRequired;
    @Value("${agent.finance.collection.whitelistRequired:${AGENT_FINANCE_COLLECTION_WHITELIST_REQUIRED:false}}")
    private boolean financeWhitelistRequired;
    @Value("${agent.knowledge.enabled:${AGENT_KNOWLEDGE_ENABLED:true}}")
    private boolean knowledgeEnabled;
    @Value("${agent.knowledge.hybridEnabled:${AGENT_KNOWLEDGE_HYBRID_ENABLED:false}}")
    private boolean hybridKnowledgeEnabled;

    public AgentWorkspaceRuntimeService(AgentRuntimeMapper mapper,
                                        ProjectMapper projectMapper,
                                        FeatureAccountWhitelistMapper whitelistMapper,
                                        AgentQuotaService quotaService,
                                        AgentRunEventService eventService,
                                        ObjectMapper objectMapper,
                                        AgentKnowledgeManagementService knowledgeManagementService) {
        this.mapper = mapper;
        this.projectMapper = projectMapper;
        this.whitelistMapper = whitelistMapper;
        this.quotaService = quotaService;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.knowledgeManagementService = knowledgeManagementService;
    }

    public Map<String, Object> capabilities(String projectId) {
        String uid = requireUid();
        String cid = requireCid();
        boolean whitelisted = !whitelistRequired || isWhitelisted(FEATURE_CODE, cid, uid);
        boolean enabled = workspaceEnabled && whitelisted;
        boolean financeWhitelisted = !financeWhitelistRequired
                || isWhitelisted(FINANCE_COLLECTION_FEATURE_CODE, cid, uid);
        boolean tenantFinanceEnabled = enabled && financeWhitelisted;
        boolean projectAllowed = true;
        if (enabled && StringUtils.hasText(projectId)) {
            projectAllowed = findProject(cid, projectId) != null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", true);
        result.put("enabled", enabled && projectAllowed);
        result.put("workspaceEnabled", workspaceEnabled);
        result.put("whitelisted", whitelisted);
        result.put("projectAllowed", projectAllowed);
        result.put("tenantFinanceEnabled", tenantFinanceEnabled);
        result.put("financeWhitelisted", financeWhitelisted);
        List<String> scopeModes = Collections.singletonList(SCOPE_UNIFIED);
        result.put("scopeModes", scopeModes);
        result.put("projectSelectionEnabled", true);
        result.put("multiProjectSelectionEnabled", true);
        result.put("mode", "READ_ONLY");
        result.put("allowedRiskLevels", java.util.Arrays.asList("READ", "COMPUTE"));
        result.put("knowledgeEnabled", knowledgeEnabled);
        boolean vectorConfigured = knowledgeManagementService.vectorConfigured();
        boolean semanticQueryEnabled = knowledgeEnabled && hybridKnowledgeEnabled && vectorConfigured;
        result.put("knowledgeMode", !knowledgeEnabled ? "DISABLED" : semanticQueryEnabled ? "HYBRID_VECTOR" : "BUILT_IN_CURATED");
        result.put("semanticQueryEnabled", semanticQueryEnabled);
        result.put("knowledgeManagementEnabled", knowledgeManagementService.manageAllowed());
        result.put("knowledgeManageAllowed", knowledgeManagementService.manageAllowed());
        result.put("vectorHealthy", vectorConfigured);
        result.put("skillLearningEnabled", false);
        result.put("writeEnabled", false);
        return result;
    }

    public AgentRuntimeRecords.Workspace createWorkspace(AgentWorkspaceRequests.CreateWorkspace request) {
        requireFeature();
        String cid = requireCid();
        String uid = requireUid();
        String scopeType = normalizeScopeType(request == null ? null : request.getScopeType());
        if (SCOPE_TENANT.equals(scopeType)) {
            AgentRuntimeRecords.Workspace existing = mapper.selectWorkspaceByScope(cid, SCOPE_TENANT, SCOPE_TENANT);
            if (existing != null) {
                return existing;
            }
            LocalDateTime now = LocalDateTime.now();
            AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
            workspace.setWorkspaceId("aws_" + CommonUtil.createUuid());
            workspace.setCid(cid);
            workspace.setScopeType(SCOPE_TENANT);
            workspace.setScopeKey(SCOPE_TENANT);
            workspace.setProjectId(null);
            workspace.setProjectBusinessType("mixed");
            workspace.setName("小云工作空间");
            workspace.setStatus("ACTIVE");
            workspace.setCreatedBy(uid);
            workspace.setCreatedAt(now);
            workspace.setUpdatedAt(now);
            try {
                mapper.insertWorkspace(workspace);
                return workspace;
            } catch (DuplicateKeyException e) {
                AgentRuntimeRecords.Workspace concurrent = mapper.selectWorkspaceByScope(cid, SCOPE_TENANT, SCOPE_TENANT);
                if (concurrent != null) {
                    return concurrent;
                }
                throw e;
            }
        }
        if (request == null || !StringUtils.hasText(request.getProjectId())) {
            throw new MyBizException("请选择项目", "AGT400");
        }
        ProjectEntity project = requireProject(cid, request.getProjectId().trim());
        AgentRuntimeRecords.Workspace existing = mapper.selectWorkspaceByScope(cid, SCOPE_PROJECT, project.getProjectId());
        if (existing == null) {
            existing = mapper.selectWorkspaceByProject(cid, project.getProjectId());
        }
        if (existing != null) {
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setWorkspaceId("aws_" + CommonUtil.createUuid());
        workspace.setCid(cid);
        workspace.setScopeType(SCOPE_PROJECT);
        workspace.setScopeKey(project.getProjectId());
        workspace.setProjectId(project.getProjectId());
        workspace.setProjectBusinessType(normalizeBusinessType(project.getProjectBusinessType()));
        workspace.setName(StringUtils.hasText(project.getProjectName()) ? project.getProjectName() : "项目工作空间");
        workspace.setStatus("ACTIVE");
        workspace.setCreatedBy(uid);
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        try {
            mapper.insertWorkspace(workspace);
            return workspace;
        } catch (DuplicateKeyException e) {
            AgentRuntimeRecords.Workspace concurrent = mapper.selectWorkspaceByScope(cid, SCOPE_PROJECT, project.getProjectId());
            if (concurrent != null) {
                return concurrent;
            }
            throw e;
        }
    }

    public AgentRuntimeRecords.Workspace getWorkspace(String workspaceId) {
        requireFeature();
        return requireWorkspace(requireCid(), workspaceId);
    }

    public List<AgentRuntimeRecords.AgentThread> listThreads(String workspaceId) {
        requireFeature();
        String cid = requireCid();
        requireWorkspace(cid, workspaceId);
        return mapper.selectThreads(cid, requireUid(), workspaceId);
    }

    public AgentRuntimeRecords.AgentThread createThread(String workspaceId, AgentWorkspaceRequests.CreateThread request) {
        requireFeature();
        String cid = requireCid();
        String uid = requireUid();
        requireWorkspace(cid, workspaceId);
        LocalDateTime now = LocalDateTime.now();
        AgentRuntimeRecords.AgentThread thread = new AgentRuntimeRecords.AgentThread();
        thread.setThreadId("ath_" + CommonUtil.createUuid());
        thread.setWorkspaceId(workspaceId);
        thread.setCid(cid);
        thread.setOwnerUid(uid);
        thread.setTitle(normalizeTitle(request == null ? null : request.getTitle(), "新对话"));
        thread.setStatus("ACTIVE");
        thread.setSummary(null);
        thread.setSummaryVersion(0);
        thread.setLastMessageAt(null);
        thread.setCreatedAt(now);
        thread.setUpdatedAt(now);
        mapper.insertThread(thread);
        return thread;
    }

    public AgentRuntimeRecords.AgentThread updateThread(String threadId, AgentWorkspaceRequests.UpdateThread request) {
        requireFeature();
        String cid = requireCid();
        String uid = requireUid();
        AgentRuntimeRecords.AgentThread thread = requireThread(cid, uid, threadId);
        String title = normalizeTitle(request == null ? null : request.getTitle(), null);
        if (!StringUtils.hasText(title)) {
            throw new MyBizException("请输入会话名称", "AGT400");
        }
        mapper.updateThreadTitle(threadId, cid, uid, title, LocalDateTime.now());
        thread.setTitle(title);
        thread.setUpdatedAt(LocalDateTime.now());
        return thread;
    }

    public Map<String, Object> archiveThread(String threadId) {
        requireFeature();
        String cid = requireCid();
        String uid = requireUid();
        requireThread(cid, uid, threadId);
        if (mapper.archiveThread(threadId, cid, uid, LocalDateTime.now()) <= 0) {
            throw new MyBizException("会话已归档或不存在", "AGT409");
        }
        return Collections.singletonMap("threadId", threadId);
    }

    public List<Map<String, Object>> listMessages(String threadId) {
        requireFeature();
        String cid = requireCid();
        requireThread(cid, requireUid(), threadId);
        List<AgentRuntimeRecords.Message> messages = mapper.selectRecentMessages(cid, threadId, 200);
        Collections.reverse(messages);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentRuntimeRecords.Message message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", message.getMessageId());
            item.put("threadId", message.getThreadId());
            item.put("runId", message.getRunId());
            item.put("role", message.getRole());
            item.put("contentType", message.getContentType());
            item.put("content", message.getContent());
            item.put("metadata", parseJson(message.getMetadataJson()));
            item.put("createdAt", message.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    public Map<String, Object> getRun(String runId) {
        requireFeature();
        AgentRuntimeRecords.Run run = requireRunAccess(runId);
        return runView(run, null);
    }

    public Map<String, Object> cancelRun(String runId) {
        requireFeature();
        String cid = requireCid();
        String uid = requireUid();
        requireRunAccess(runId);
        int updated = mapper.cancelRun(runId, cid, uid, LocalDateTime.now());
        if (updated > 0) {
            eventService.publish(runId, "run.status", eventPayload("status", "CANCELLED"));
            eventService.complete(runId);
        }
        AgentRuntimeRecords.Run current = mapper.selectRun(runId);
        return runView(current, null);
    }

    public List<Map<String, Object>> listArtifacts(String threadId) {
        requireFeature();
        String cid = requireCid();
        requireThread(cid, requireUid(), threadId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentRuntimeRecords.Artifact artifact : mapper.selectArtifacts(cid, threadId, 100)) {
            result.add(artifactView(artifact, false));
        }
        return result;
    }

    public Map<String, Object> getArtifact(String artifactId) {
        requireFeature();
        String cid = requireCid();
        AgentRuntimeRecords.Artifact artifact = mapper.selectArtifact(cid, artifactId);
        if (artifact == null) {
            throw new MyBizException("产物不存在", "AGT404");
        }
        requireThread(cid, requireUid(), artifact.getThreadId());
        return artifactView(artifact, true);
    }

    public AgentRuntimeRecords.Artifact requireArtifactAccess(String artifactId) {
        requireFeature();
        String cid = requireCid();
        AgentRuntimeRecords.Artifact artifact = mapper.selectArtifact(cid, artifactId);
        if (artifact == null) {
            throw new MyBizException("产物不存在", "AGT404");
        }
        requireThread(cid, requireUid(), artifact.getThreadId());
        return artifact;
    }

    public SseEmitter subscribe(String runId, int afterSeq) {
        requireFeature();
        AgentRuntimeRecords.Run run = requireRunAccess(runId);
        return eventService.subscribe(runId, afterSeq, isTerminal(run.getStatus()));
    }

    public AgentRuntimeRecords.Run requireRunAccess(String runId) {
        String cid = requireCid();
        String uid = requireUid();
        AgentRuntimeRecords.Run run = mapper.selectRun(runId);
        if (run == null || !cid.equals(run.getCid()) || !uid.equals(run.getOwnerUid())) {
            throw new MyBizException("执行不存在或无权限", "AGT404");
        }
        requireThread(cid, uid, run.getThreadId());
        return run;
    }

    private void requireFeature() {
        Map<String, Object> capability = capabilities(null);
        if (!Boolean.TRUE.equals(capability.get("enabled"))) {
            throw new MyBizException("小云暂未开通", "AGT403");
        }
    }

    private void requireTenantFinanceFeature() {
        requireFeature();
        String cid = requireCid();
        String uid = requireUid();
        if (financeWhitelistRequired && !isWhitelisted(FINANCE_COLLECTION_FEATURE_CODE, cid, uid)) {
            throw new MyBizException("企业财务分析能力暂未开通", "AGT403");
        }
    }

    private boolean isWhitelisted(String featureCode, String cid, String uid) {
        LocalDateTime now = LocalDateTime.now();
        QueryWrapper<FeatureAccountWhitelistEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("feature_code", featureCode)
                .eq("cid", cid)
                .eq("uid", uid)
                .eq("enabled", 1)
                .and(item -> item.isNull("valid_from").or().le("valid_from", now))
                .and(item -> item.isNull("valid_to").or().ge("valid_to", now));
        return whitelistMapper.selectCount(wrapper) > 0;
    }

    private AgentRuntimeRecords.Workspace requireWorkspace(String cid, String workspaceId) {
        if (!StringUtils.hasText(workspaceId)) {
            throw new MyBizException("工作空间不能为空", "AGT400");
        }
        AgentRuntimeRecords.Workspace workspace = mapper.selectWorkspaceById(cid, workspaceId);
        if (workspace == null || !"ACTIVE".equals(workspace.getStatus())) {
            throw new MyBizException("工作空间不存在或无权限", "AGT404");
        }
        if (!SCOPE_TENANT.equals(normalizeScopeType(workspace.getScopeType()))) {
            requireProject(cid, workspace.getProjectId());
        }
        return workspace;
    }

    private AgentRuntimeRecords.AgentThread requireThread(String cid, String uid, String threadId) {
        if (!StringUtils.hasText(threadId)) {
            throw new MyBizException("会话不能为空", "AGT400");
        }
        AgentRuntimeRecords.AgentThread thread = mapper.selectThread(cid, threadId);
        if (thread == null || !uid.equals(thread.getOwnerUid()) || !"ACTIVE".equals(thread.getStatus())) {
            throw new MyBizException("会话不存在或无权限", "AGT404");
        }
        requireWorkspace(cid, thread.getWorkspaceId());
        return thread;
    }

    private ProjectEntity requireProject(String cid, String projectId) {
        ProjectEntity project = findProject(cid, projectId);
        if (project == null) {
            throw new MyBizException("项目不存在或无权限", "PRCT404");
        }
        return project;
    }

    private ProjectEntity findProject(String cid, String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return null;
        }
        return projectMapper.selectOne(new QueryWrapper<ProjectEntity>()
                .eq("cid", cid)
                .eq("project_id", projectId)
                .last("LIMIT 1"));
    }

    private Map<String, Object> runView(AgentRuntimeRecords.Run run, Object quota) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (run == null) {
            return result;
        }
        result.put("runId", run.getRunId());
        result.put("traceId", run.getRunId());
        result.put("threadId", run.getThreadId());
        result.put("workspaceId", run.getWorkspaceId());
        result.put("status", run.getStatus());
        result.put("provider", run.getModelProvider());
        result.put("model", run.getModelName());
        result.put("reasoningEffort", run.getReasoningEffort());
        result.put("iterationCount", run.getIterationCount());
        result.put("toolCallCount", run.getToolCallCount());
        result.put("promptTokens", run.getPromptTokens());
        result.put("completionTokens", run.getCompletionTokens());
        result.put("startedAt", run.getStartedAt());
        result.put("heartbeatAt", run.getHeartbeatAt());
        result.put("completedAt", run.getCompletedAt());
        result.put("errorCode", run.getErrorCode());
        result.put("errorMessage", run.getErrorMessage());
        if (quota != null) {
            result.put("quota", quota);
        }
        return result;
    }

    private Map<String, Object> artifactView(AgentRuntimeRecords.Artifact artifact, boolean includeContent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifactId", artifact.getArtifactId());
        result.put("threadId", artifact.getThreadId());
        result.put("runId", artifact.getRunId());
        result.put("workspaceId", artifact.getWorkspaceId());
        result.put("projectId", artifact.getProjectId());
        result.put("artifactType", artifact.getArtifactType());
        result.put("title", artifact.getTitle());
        result.put("mimeType", artifact.getMimeType());
        result.put("status", artifact.getStatus());
        if (includeContent) {
            result.put("content", parseJson(artifact.getContentJson()));
        }
        result.put("createdAt", artifact.getCreatedAt());
        return result;
    }

    private Object parseJson(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception e) {
            return value;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new MyBizException("项目范围保存失败", "AGT500");
        }
    }

    private Map<String, Object> eventPayload(String key, Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, value);
        return payload;
    }

    private int estimateTokens(String text) {
        return StringUtils.hasText(text) ? (int) Math.ceil(text.length() * 1.2d) : 0;
    }

    private String normalizeTitle(String value, String fallback) {
        String normalized = StringUtils.hasText(value) ? value.trim() : fallback;
        if (!StringUtils.hasText(normalized)) {
            return normalized;
        }
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 59) + "…";
    }

    private String normalizeBusinessType(String value) {
        return "rent_in".equalsIgnoreCase(value) ? "rent_in" : "rent_out";
    }

    private String normalizeScopeType(String value) {
        return SCOPE_TENANT.equalsIgnoreCase(value) || SCOPE_UNIFIED.equalsIgnoreCase(value)
                ? SCOPE_TENANT : SCOPE_PROJECT;
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)
                || "INTERRUPTED".equals(status);
    }

    private String requireUid() {
        String uid = AuthContext.getUid();
        if (!StringUtils.hasText(uid)) {
            throw new MyBizException("未登录或登录已失效", "AUTH401");
        }
        return uid;
    }

    private String requireCid() {
        String cid = AuthContext.getCid();
        if (!StringUtils.hasText(cid)) {
            cid = CommonUtil.getCid();
        }
        return cid;
    }
}
