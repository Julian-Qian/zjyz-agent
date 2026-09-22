package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.model.AgentQuotaDecision;
import com.zjyz.agent.service.AgentQuotaService;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.service.AgentRunEventService;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.common.security.AuthContext;
import com.zjyz.common.util.CommonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Submission/read API for persistent Tasks and short V2 Runs. No model call blocks the HTTP turn. */
@Service
@Slf4j
public class AgentV2CoordinatorService {
    private final AgentRuntimeMapper runtimeMapper;
    private final AgentV2Mapper v2Mapper;
    private final AgentQuotaService quotaService;
    private final AgentRunEventService eventService;
    private final AgentV2ScopeService scopeService;
    private final AgentV2ContextBuilder contextBuilder;
    private final AgentV2CapabilityManifestService manifestService;
    private final AgentV2TaskStateService taskStateService;
    private final AgentV2RunExecutor runExecutor;
    private final Executor executor;
    private final ObjectMapper objectMapper;

    @Value("${agent.runtime.maxConcurrentPerUser:2}")
    private int maxConcurrentPerUser;
    @Value("${agent.runtime.maxInputChars:4000}")
    private int maxInputChars;

    public AgentV2CoordinatorService(AgentRuntimeMapper runtimeMapper,
                                     AgentV2Mapper v2Mapper,
                                     AgentQuotaService quotaService,
                                     AgentRunEventService eventService,
                                     AgentV2ScopeService scopeService,
                                     AgentV2ContextBuilder contextBuilder,
                                     AgentV2CapabilityManifestService manifestService,
                                     AgentV2TaskStateService taskStateService,
                                     AgentV2RunExecutor runExecutor,
                                     @Qualifier("agentRunExecutor") Executor executor,
                                     ObjectMapper objectMapper) {
        this.runtimeMapper = runtimeMapper;
        this.v2Mapper = v2Mapper;
        this.quotaService = quotaService;
        this.eventService = eventService;
        this.scopeService = scopeService;
        this.contextBuilder = contextBuilder;
        this.manifestService = manifestService;
        this.taskStateService = taskStateService;
        this.runExecutor = runExecutor;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> createTurn(String threadId, AgentV2Models.CreateTurnRequest request) {
        return createTurnInternal(threadId, request, null, true);
    }

    @Transactional
    public Map<String, Object> createTurnFromInteraction(String threadId,
                                                         AgentV2Models.CreateTurnRequest request,
                                                         String parentTaskId) {
        return createTurnInternal(threadId, request, parentTaskId, false);
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zjyz.agent.workspace.attachment.AgentAttachmentService attachmentService;

    private Map<String, Object> createTurnInternal(String threadId,
                                                   AgentV2Models.CreateTurnRequest request,
                                                   String forcedParentTaskId,
                                                   boolean consumeTaskQuota) {
        return createTurnInternal(threadId, request, forcedParentTaskId, consumeTaskQuota, null);
    }

    private Map<String,Object> createTurnInternal(String threadId, AgentV2Models.CreateTurnRequest request,
                                                   String forcedParentTaskId, boolean consumeTaskQuota,
                                                   AgentV2Models.ScopeSnapshot retryScope) {
        String cid = requireCid();
        String uid = requireUid();
        AgentRuntimeRecords.AgentThread thread = requireThread(cid, uid, threadId);
        AgentRuntimeRecords.Workspace workspace = requireWorkspace(cid, thread.getWorkspaceId());
        String message = request == null ? null : request.getMessage();
        if (!StringUtils.hasText(message)) {
            throw new MyBizException("请输入问题", "AGT400");
        }
        message = message.trim();
        if (message.length() > Math.max(maxInputChars, 1000)) {
            throw new MyBizException("输入内容过长", "AGT400");
        }

        String clientRequestId = request.getClientRequestId();
        clientRequestId = StringUtils.hasText(clientRequestId)
                ? clientRequestId.trim() : "av2_" + CommonUtil.createUuid();
        AgentV2Models.Task previousTask = resolvePreviousTask(cid, uid, threadId, forcedParentTaskId);
        AgentV2Models.ScopeSnapshot scope = scopeService.attachConversationContext(
                retryScope == null ? scopeService.freeze(workspace, request, cid) : retryScope, previousTask);
        String fingerprint = requestFingerprint(message, scope, request.getContext(), request.getAttachmentIds());
        AgentV2Models.Task existing = v2Mapper.selectTaskByClientRequest(cid, uid, clientRequestId);
        if (existing != null) {
            if (!fingerprint.equals(existing.getRequestFingerprint()) || !threadId.equals(existing.getThreadId())) {
                throw new MyBizException("该请求已被其他任务使用，请重新提交", "AGT409");
            }
            return taskSubmissionView(existing, parseJson(existing.getScopeJson()));
        }
        // Idempotent replay is resolved before the rollout gate. If the first V2 submission
        // committed but its HTTP response was lost, disabling V2 must not make the client fall
        // back to V1 and execute the same request twice.
        Map<String, Object> genericManifest = manifestService.manifest(null);
        if (!Boolean.TRUE.equals(genericManifest.get("v2Enabled"))) {
            throw new MyBizException("小云暂未开放", "AGT_V2_DISABLED");
        }
        if (!Boolean.TRUE.equals(genericManifest.get("enabled"))) {
            throw new MyBizException("小云暂未开通", "AGT403");
        }
        if (runtimeMapper.countActiveRuns(cid, uid) >= Math.max(maxConcurrentPerUser, 1)) {
            throw new MyBizException("同时执行的任务过多，请等待当前任务完成", "AGT429");
        }

        AgentRuntimeRecords.Workspace scopedWorkspace = scopeService.apply(workspace, scope, cid);
        Map<String, Object> manifest = manifestService.manifest(scopedWorkspace);
        scopedWorkspace.setFinanceEnabled(manifestService.financeEnabled(manifest));
        Map<String, Object> uiContext = new LinkedHashMap<>();
        if (request.getContext() != null) {
            uiContext.putAll(request.getContext());
        }
        if (StringUtils.hasText(request.getTimezone())) {
            uiContext.put("timezone", request.getTimezone().trim());
        }
        AgentV2Models.BoundedContext boundedContext = contextBuilder.build(thread,
                runtimeMapper.selectRecentMessages(cid, threadId, 30), previousTask, message, uiContext);

        String zone = String.valueOf(uiContext.getOrDefault("timezone", "Asia/Shanghai"));
        if (!java.util.Arrays.asList("Asia/Shanghai", "Asia/Singapore", "UTC").contains(zone)) zone = "Asia/Shanghai";
        boundedContext.setExecutionDate(java.time.LocalDate.now(java.time.ZoneId.of(zone)).toString());
        if (request.getAttachmentIds() != null && !request.getAttachmentIds().isEmpty()) {
            if (attachmentService == null) throw new MyBizException("文件审阅服务尚未启用", "ATTACHMENT_UNAVAILABLE");
            boundedContext.setAttachmentRefs(attachmentService.freeze(threadId, request.getAttachmentIds()));
        }
        LocalDateTime now = LocalDateTime.now();
        AgentRuntimeRecords.Run run = new AgentRuntimeRecords.Run();
        run.setRunId("arn_" + CommonUtil.createUuid());
        run.setThreadId(threadId);
        run.setWorkspaceId(thread.getWorkspaceId());
        run.setCid(cid);
        run.setOwnerUid(uid);
        run.setClientRequestId(clientRequestId);
        run.setStatus("QUEUED");
        run.setReasoningEffort("medium");
        run.setIterationCount(0);
        run.setToolCallCount(0);
        run.setPromptTokens(0);
        run.setCompletionTokens(0);
        run.setEstimatedCostCny(BigDecimal.ZERO);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);

        AgentV2Models.Task task = new AgentV2Models.Task();
        task.setTaskId("atk_" + CommonUtil.createUuid());
        task.setTurnId("atn_" + CommonUtil.createUuid());
        task.setParentTaskId(previousTask == null ? null : previousTask.getTaskId());
        task.setThreadId(threadId);
        task.setWorkspaceId(thread.getWorkspaceId());
        task.setLatestRunId(run.getRunId());
        task.setCid(cid);
        task.setOwnerUid(uid);
        task.setClientRequestId(clientRequestId);
        task.setRequestFingerprint(fingerprint);
        task.setStatus("OPEN");
        task.setDialogueAct("PENDING");
        task.setRelationType("PENDING");
        task.setGoal(message);
        task.setScopeJson(toJson(scope));
        task.setContextJson(toJson(boundedContext));
        task.setCapabilitySnapshotJson(toJson(manifest));
        task.setVersion(1);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        try {
            v2Mapper.insertTask(task);
        } catch (DuplicateKeyException duplicate) {
            AgentV2Models.Task concurrent = v2Mapper.selectTaskByClientRequest(cid, uid, clientRequestId);
            if (concurrent != null && fingerprint.equals(concurrent.getRequestFingerprint())
                    && threadId.equals(concurrent.getThreadId())) {
                return taskSubmissionView(concurrent, parseJson(concurrent.getScopeJson()));
            }
            throw new MyBizException("该请求已被其他任务使用，请重新提交", "AGT409");
        }
        runtimeMapper.insertRun(run);
        v2Mapper.linkRun(run.getRunId(), task.getTaskId(), task.getTurnId());
        if (consumeTaskQuota) {
            AgentQuotaDecision quotaDecision = quotaService.consumeTenantTask(uid);
            if (quotaDecision == null || !quotaDecision.isAllow()) {
                throw new MyBizException(quotaDecision == null ? "企业共享任务额度检查失败" : quotaDecision.getReason(),
                        "AGT429");
            }
        }

        AgentRuntimeRecords.Message userMessage = new AgentRuntimeRecords.Message();
        userMessage.setMessageId("ams_" + CommonUtil.createUuid());
        userMessage.setThreadId(threadId);
        userMessage.setRunId(run.getRunId());
        userMessage.setCid(cid);
        userMessage.setRole("user");
        userMessage.setContentType("TEXT");
        userMessage.setContent(message);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runtimeVersion", AgentV2Models.RUNTIME_VERSION);
        metadata.put("taskId", task.getTaskId());
        metadata.put("turnId", task.getTurnId());
        metadata.put("attachmentIds", request.getAttachmentIds());
        metadata.put("projectScope", scopeMetadata(scope));
        metadata.put("contextBudget", contextBudgetMetadata(boundedContext));
        userMessage.setMetadataJson(toJson(metadata));
        userMessage.setTokenCount(estimateTokens(message));
        userMessage.setCreatedAt(now);
        runtimeMapper.insertMessage(userMessage);
        v2Mapper.linkMessage(userMessage.getMessageId(), task.getTaskId(), task.getTurnId());
        runtimeMapper.touchThread(threadId, now);
        if ("新对话".equals(thread.getTitle())) {
            runtimeMapper.updateThreadTitle(threadId, cid, uid, normalizeTitle(message), now);
        }

        scheduleAfterCommit(task, run, consumeTaskQuota, manifest);
        return taskSubmissionView(task, scope);
    }

    public Map<String, Object> getTask(String taskId) {
        AgentV2Models.Task task = requireTaskAccess(taskId);
        task = taskStateService.reconcile(task);
        Map<String, Object> result = taskSubmissionView(task, parseJson(task.getScopeJson()));
        result.put("dialogueAct", task.getDialogueAct());
        result.put("relationType", task.getRelationType());
        result.put("goal", task.getGoal());
        result.put("interpretation", parseJson(task.getInterpretationJson()));
        result.put("taskSpec", parseJson(task.getTaskSpecJson()));
        result.put("context", parseJson(task.getContextJson()));
        AgentV2Models.Interaction interaction = v2Mapper.selectLatestInteraction(taskId);
        if ("WAITING_USER".equals(task.getStatus()) && interaction != null && "PENDING".equals(interaction.getStatus())) {
            result.put("interaction", interactionView(interaction));
        }
        AgentRuntimeRecords.Run run = runtimeMapper.selectRun(task.getLatestRunId());
        if (run != null) {
            Map<String, Object> runView = new LinkedHashMap<>();
            runView.put("runId", run.getRunId());
            runView.put("status", run.getStatus());
            runView.put("errorCode", run.getErrorCode());
            runView.put("errorMessage", run.getErrorMessage());
            runView.put("completedAt", run.getCompletedAt());
            result.put("run", runView);
            AgentV2Models.RunState state=v2Mapper.selectRunState(run.getRunId());
            if(state!=null)result.put("outcome", parseJson(state.getOutcomeJson()));
        }
        return result;
    }

    public Map<String, Object> getActiveTask(String threadId) {
        String cid = requireCid();
        String uid = requireUid();
        requireThread(cid, uid, threadId);
        for (int attempt = 0; attempt < 10; attempt++) {
            AgentV2Models.Task task = v2Mapper.selectLatestActiveTask(cid, uid, threadId);
            if (task == null) {
                return null;
            }
            AgentV2Models.Task reconciled = taskStateService.reconcile(task);
            if (reconciled != null && ("OPEN".equals(reconciled.getStatus())
                    || "READY".equals(reconciled.getStatus()) || "WAITING_USER".equals(reconciled.getStatus()))) {
                return getTask(reconciled.getTaskId());
            }
        }
        return null;
    }

    public Map<String, Object> cancelTask(String taskId) {
        AgentV2Models.Task task = requireTaskAccess(taskId);
        AgentV2Models.Task cancelled = taskStateService.cancel(task);
        AgentRuntimeRecords.Run run = runtimeMapper.selectRun(cancelled.getLatestRunId());
        if (run != null) {
            Map<String, Object> taskEvent = eventPayload(cancelled, run, "status", cancelled.getStatus());
            eventService.publish(run.getRunId(), "task.status", taskEvent);
            if ("CANCELLED".equals(run.getStatus())) {
                eventService.publish(run.getRunId(), "run.status",
                        eventPayload(cancelled, run, "status", "CANCELLED"));
            }
            eventService.complete(run.getRunId());
        }
        return getTask(cancelled.getTaskId());
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Map<String,Object> retryTask(String taskId, Map<String,Object> input) {
        AgentV2Models.Task old=requireTaskAccess(taskId);
        if(input==null || !StringUtils.hasText(String.valueOf(input.getOrDefault("clientRequestId",""))))
            throw new MyBizException("缺少重试请求标识", "AGT400");
        if(!java.util.Objects.equals(String.valueOf(old.getVersion()),String.valueOf(input.get("expectedTaskVersion"))))
            throw new MyBizException("任务版本已变化，请刷新后重试", "AGT409");
        if(!java.util.Arrays.asList("BLOCKED","CANCELLED").contains(old.getStatus()))
            throw new MyBizException("当前任务不能重试", "AGT409");
        AgentV2Models.CreateTurnRequest request=new AgentV2Models.CreateTurnRequest();
        request.setMessage(old.getGoal());request.setClientRequestId(String.valueOf(input.get("clientRequestId")));
        AgentV2Models.ScopeSnapshot scope=objectMapper.convertValue(parseJson(old.getScopeJson()),AgentV2Models.ScopeSnapshot.class);
        AgentV2Models.ScopeSelection selection=new AgentV2Models.ScopeSelection();selection.setSelectionMode("EXPLICIT");
        selection.setProjectIds(scope == null ? java.util.Collections.emptyList() : scope.getProjectIds());selection.setExplicitOverride(true);request.setScopeSelection(selection);
        AgentV2Models.BoundedContext context=objectMapper.convertValue(parseJson(old.getContextJson()),AgentV2Models.BoundedContext.class);
        java.util.List<String> ids=new java.util.ArrayList<>();
        if(context!=null && context.getAttachmentRefs()!=null) for(Map<String,Object> ref:context.getAttachmentRefs())ids.add(String.valueOf(ref.get("attachmentId")));
        request.setAttachmentIds(ids);
        request.setContext(java.util.Collections.singletonMap("retryOfTaskId",old.getTaskId()));
        // Keep the original frozen scope, including an empty scope. apply() rechecks access;
        // retries must never expand ALL to projects created after the original request.
        if(scope==null || !scope.isFrozen() || scope.getProjectIds()==null)
            throw new MyBizException("原任务项目范围缺失，请重新提交问题", "AGT409");
        scope.setCandidateProjectIds(new java.util.ArrayList<>(scope.getProjectIds()));
        scope.setCandidateRequestedSelectionMode("EXPLICIT");
        scope.setCandidateScopeHash(scope.getScopeHash());
        scope.setExplicitOverride(true);
        Map<String,Object> created=createTurnInternal(old.getThreadId(),request,old.getTaskId(),true,scope);
        created.put("retryOfTaskId",old.getTaskId());return created;
    }

    public Map<String, Object> capabilities(String scopeMode, List<String> projectIds) {
        if (!StringUtils.hasText(scopeMode)) {
            return manifestService.manifest(null);
        }
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid(requireCid());
        workspace.setScopeType("TENANT");
        workspace.setProjectIds(Collections.emptyList());
        AgentV2Models.ScopeSelection selection = new AgentV2Models.ScopeSelection();
        selection.setSelectionMode(scopeMode);
        selection.setProjectIds("ALL".equalsIgnoreCase(scopeMode)
                ? Collections.emptyList() : projectIds == null ? Collections.emptyList() : projectIds);
        selection.setExplicitOverride(true);
        AgentV2Models.CreateTurnRequest request = new AgentV2Models.CreateTurnRequest();
        request.setScopeSelection(selection);
        AgentV2Models.ScopeSnapshot snapshot = scopeService.freeze(workspace, request, workspace.getCid());
        AgentRuntimeRecords.Workspace scoped = scopeService.apply(workspace, snapshot, workspace.getCid());
        Map<String, Object> manifest = manifestService.manifest(scoped);
        scoped.setFinanceEnabled(manifestService.financeEnabled(manifest));
        return manifest;
    }

    AgentV2Models.Task requireTaskAccess(String taskId) {
        AgentV2Models.Task task = v2Mapper.selectTask(taskId);
        if (task == null || !requireCid().equals(task.getCid()) || !requireUid().equals(task.getOwnerUid())) {
            throw new MyBizException("任务不存在或无权限", "AGT404");
        }
        requireThread(task.getCid(), task.getOwnerUid(), task.getThreadId());
        return task;
    }

    private AgentV2Models.Task resolvePreviousTask(String cid,
                                                   String uid,
                                                   String threadId,
                                                   String forcedParentTaskId) {
        if (!StringUtils.hasText(forcedParentTaskId)) {
            return v2Mapper.selectLatestTask(cid, uid, threadId);
        }
        AgentV2Models.Task task = v2Mapper.selectTask(forcedParentTaskId);
        if (task == null || !cid.equals(task.getCid()) || !uid.equals(task.getOwnerUid())
                || !threadId.equals(task.getThreadId())) {
            throw new MyBizException("关联任务不存在或无权限", "AGT404");
        }
        return task;
    }

    void requireV2Enabled() {
        manifestService.requireEnabled();
    }

    private void scheduleAfterCommit(AgentV2Models.Task task,
                                     AgentRuntimeRecords.Run run,
                                     boolean taskQuotaConsumed,
                                     Map<String, Object> manifest) {
        Runnable dispatch = () -> {
            publishSubmissionEvents(task, run, manifest);
            try {
                executor.execute(() -> runExecutor.execute(task.getTaskId(), run.getRunId()));
            } catch (RejectedExecutionException rejected) {
                LocalDateTime now = LocalDateTime.now();
                run.setStatus("FAILED");
                run.setHeartbeatAt(now);
                run.setCompletedAt(now);
                run.setUpdatedAt(now);
                run.setErrorCode("AGT429");
                run.setErrorMessage("执行队列已满");
                if (v2Mapper.failActiveRun(run.getRunId(), task.getTaskId(), run.getCid(), run.getOwnerUid(),
                        "AGT429", "执行队列已满", now) > 0) {
                    taskStateService.block(task.getTaskId());
                    if (taskQuotaConsumed) {
                        quotaService.releaseTenantTask(run.getCid(), YearMonth.from(run.getCreatedAt()).toString());
                    }
                    eventService.publish(run.getRunId(), "run.failed",
                            eventPayload(task, run, "message", "执行队列已满，请稍后重试"));
                    eventService.complete(run.getRunId());
                }
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                dispatch.run();
            }
        });
    }

    private void publishSubmissionEvents(AgentV2Models.Task task,
                                         AgentRuntimeRecords.Run run,
                                         Map<String, Object> manifest) {
        try {
            eventService.publish(run.getRunId(), "run.created",
                    eventPayload(task, run, "status", "QUEUED"));
            eventService.publish(run.getRunId(), "run.status",
                    eventPayload(task, run, "status", "QUEUED"));
            eventService.publish(run.getRunId(), "task.status",
                    eventPayload(task, run, "status", "OPEN"));
            Map<String, Object> capabilityEvent = eventPayload(task, run, "readOnly", true);
            capabilityEvent.put("count", enabledCapabilityCount(manifest));
            eventService.publish(run.getRunId(), "capability.resolved", capabilityEvent);
        } catch (Exception error) {
            log.warn("V2 submission event publish failed, runId={}, error={}",
                    run.getRunId(), error.getMessage());
        }
    }

    private AgentRuntimeRecords.AgentThread requireThread(String cid, String uid, String threadId) {
        if (!StringUtils.hasText(threadId)) {
            throw new MyBizException("会话不能为空", "AGT400");
        }
        AgentRuntimeRecords.AgentThread thread = runtimeMapper.selectThread(cid, threadId);
        if (thread == null || !uid.equals(thread.getOwnerUid()) || !"ACTIVE".equals(thread.getStatus())) {
            throw new MyBizException("会话不存在或无权限", "AGT404");
        }
        requireWorkspace(cid, thread.getWorkspaceId());
        return thread;
    }

    private AgentRuntimeRecords.Workspace requireWorkspace(String cid, String workspaceId) {
        AgentRuntimeRecords.Workspace workspace = runtimeMapper.selectWorkspaceById(cid, workspaceId);
        if (workspace == null || !"ACTIVE".equals(workspace.getStatus())) {
            throw new MyBizException("工作空间不存在或无权限", "AGT404");
        }
        return workspace;
    }

    private Map<String, Object> taskSubmissionView(AgentV2Models.Task task, Object scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runtimeVersion", AgentV2Models.RUNTIME_VERSION);
        result.put("taskId", task.getTaskId());
        result.put("turnId", task.getTurnId());
        result.put("contextTaskId", task.getParentTaskId());
        result.put("runId", task.getLatestRunId());
        result.put("threadId", task.getThreadId());
        result.put("workspaceId", task.getWorkspaceId());
        AgentRuntimeRecords.Run currentRun = runtimeMapper.selectRun(task.getLatestRunId());
        result.put("status", currentRun == null ? "UNKNOWN" : currentRun.getStatus());
        result.put("taskStatus", task.getStatus());
        result.put("version", task.getVersion());
        result.put("scope", scope);
        result.put("scopeSnapshot", scope);
        result.put("streamUrl", "/agent/runs/" + task.getLatestRunId() + "/events");
        Map<String, Object> cursor = new LinkedHashMap<>();
        cursor.put("runId", task.getLatestRunId());
        cursor.put("seqKind", "RUN");
        cursor.put("afterSeq", 0);
        cursor.put("eventsUrl", "/agent/runs/" + task.getLatestRunId() + "/events");
        result.put("eventCursor", cursor);
        return result;
    }

    private Map<String, Object> interactionView(AgentV2Models.Interaction value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("interactionId", value.getInteractionId());
        result.put("taskId", value.getTaskId());
        result.put("type", value.getInteractionType());
        result.put("status", value.getStatus());
        result.put("prompt", value.getPromptText());
        result.put("missingFields", parseJson(value.getMissingFieldsJson()));
        result.put("options", parseJson(value.getOptionsJson()));
        result.put("answer", parseJson(value.getAnswerJson()));
        return result;
    }

    private Map<String, Object> scopeMetadata(AgentV2Models.ScopeSnapshot scope) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("selectionMode", scope.getEffectiveSelectionMode());
        result.put("requestedSelectionMode", scope.getRequestedSelectionMode());
        result.put("projectIds", scope.getProjectIds());
        result.put("scopeHash", scope.getScopeHash());
        result.put("frozen", true);
        return result;
    }

    private Map<String, Object> contextBudgetMetadata(AgentV2Models.BoundedContext context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxCharacters", context.getMaxCharacters());
        result.put("usedCharacters", context.getUsedCharacters());
        result.put("messageCount", context.getMessages().size());
        result.put("truncated", context.isTruncated());
        return result;
    }

    @SuppressWarnings("unchecked")
    private int enabledCapabilityCount(Map<String, Object> manifest) {
        Object values = manifest.get("capabilities");
        if (!(values instanceof List)) {
            return 0;
        }
        int count = 0;
        for (Object value : (List<Object>) values) {
            if (value instanceof AgentV2Models.Capability
                    && ((AgentV2Models.Capability) value).isEnabled()) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> eventPayload(AgentV2Models.Task task,
                                             AgentRuntimeRecords.Run run,
                                             String key,
                                             Object value) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getTaskId());
        payload.put("turnId", task.getTurnId());
        payload.put("runId", run.getRunId());
        payload.put("schemaVersion", "2.0");
        payload.put("occurredAt", LocalDateTime.now());
        payload.put(key, value);
        return payload;
    }

    private Object parseJson(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new MyBizException("任务保存失败，请稍后重试", "AGT500");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : hash) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("无法生成任务摘要", error);
        }
    }

    String requestFingerprint(String message,
                              AgentV2Models.ScopeSnapshot scope,
                              Map<String, Object> context,
                              List<String> attachmentIds) {
        Map<String, Object> stable = new LinkedHashMap<>();
        stable.put("message", message == null ? "" : message.trim());
        String selectionMode = scope == null ? null : scope.getCandidateRequestedSelectionMode();
        stable.put("selectionMode", selectionMode);
        List<String> projects = scope == null || scope.getCandidateProjectIds() == null
                || "ALL".equalsIgnoreCase(selectionMode)
                ? new ArrayList<>() : new ArrayList<>(scope.getCandidateProjectIds());
        Collections.sort(projects);
        stable.put("projectIds", projects);
        stable.put("explicitOverride", scope != null && scope.isExplicitOverride());
        stable.put("context", context == null ? Collections.emptyMap() : context);
        List<String> attachments = attachmentIds == null ? new ArrayList<>() : new ArrayList<>(attachmentIds);
        Collections.sort(attachments);
        stable.put("attachmentIds", attachments);
        return sha256(canonicalJson(stable));
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(canonicalValue(objectMapper.valueToTree(value)));
        } catch (Exception error) {
            throw new MyBizException("请求处理失败，请稍后重试", "AGT500");
        }
    }

    private Object canonicalValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), canonicalValue(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(item -> result.add(canonicalValue(item)));
            return result;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asText();
    }

    private int estimateTokens(String text) {
        return StringUtils.hasText(text) ? (int) Math.ceil(text.length() * 1.2d) : 0;
    }

    private String normalizeTitle(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "新对话";
        return normalized.length() <= 60 ? normalized : normalized.substring(0, 59) + "…";
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
