package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.service.AgentQuotaService;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.service.AgentRunEventService;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.common.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentV2CoordinatorServiceTest {

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void activeTaskReturnsNullWhenThreadHasNoNonTerminalTask() {
        AgentRuntimeMapper runtime = mock(AgentRuntimeMapper.class);
        AgentV2Mapper v2 = mock(AgentV2Mapper.class);
        AgentRuntimeRecords.AgentThread thread = new AgentRuntimeRecords.AgentThread();
        thread.setThreadId("thread-1");
        thread.setWorkspaceId("workspace-1");
        thread.setOwnerUid("uid-1");
        thread.setStatus("ACTIVE");
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setWorkspaceId("workspace-1");
        workspace.setStatus("ACTIVE");
        when(runtime.selectThread("cid-1", "thread-1")).thenReturn(thread);
        when(runtime.selectWorkspaceById("cid-1", "workspace-1")).thenReturn(workspace);
        when(v2.selectLatestActiveTask("cid-1", "uid-1", "thread-1")).thenReturn(null);
        AuthContext.set("uid-1", "cid-1");

        AgentV2CoordinatorService service = new AgentV2CoordinatorService(
                runtime, v2, mock(AgentQuotaService.class), mock(AgentRunEventService.class),
                mock(AgentV2ScopeService.class), mock(AgentV2ContextBuilder.class),
                mock(AgentV2CapabilityManifestService.class), mock(AgentV2TaskStateService.class),
                mock(AgentV2RunExecutor.class),
                Runnable::run, new ObjectMapper());

        assertNull(service.getActiveTask("thread-1"));
    }

    @Test
    void allFingerprintUsesRawRequestRatherThanTimeVaryingExpandedProjectSet() {
        AgentV2CoordinatorService service = service();
        AgentV2Models.ScopeSnapshot first = scope("ALL", Arrays.asList("p1", "p2"));
        first.setFrozenAt(LocalDateTime.of(2026, 8, 29, 0, 0));
        AgentV2Models.ScopeSnapshot later = scope("ALL", Arrays.asList("p1", "p2", "p3"));
        later.setFrozenAt(LocalDateTime.of(2026, 8, 29, 1, 0));
        Map<String, Object> firstContext = new LinkedHashMap<>();
        firstContext.put("page", "agent");
        firstContext.put("objectId", "1");
        Map<String, Object> sameContextDifferentOrder = new LinkedHashMap<>();
        sameContextDifferentOrder.put("objectId", "1");
        sameContextDifferentOrder.put("page", "agent");

        String firstHash = service.requestFingerprint("查一下", first, firstContext,
                Arrays.asList("b", "a"));
        String retryHash = service.requestFingerprint("查一下", later, sameContextDifferentOrder,
                Arrays.asList("a", "b"));

        assertEquals(firstHash, retryHash);
        assertNotEquals(firstHash, service.requestFingerprint("换一个问题", later,
                sameContextDifferentOrder, Arrays.asList("a", "b")));
    }

    @Test
    void explicitFingerprintChangesWhenProjectSelectionChanges() {
        AgentV2CoordinatorService service = service();

        String first = service.requestFingerprint("查一下", scope("EXPLICIT", Collections.singletonList("p1")),
                Collections.emptyMap(), Collections.emptyList());
        String changed = service.requestFingerprint("查一下", scope("EXPLICIT", Collections.singletonList("p2")),
                Collections.emptyMap(), Collections.emptyList());

        assertNotEquals(first, changed);
    }

    @Test
    void identicalReplayReturnsOriginalTaskAndChangedRequestConflicts() {
        CoordinatorFixture fixture = fixture();
        AgentV2Models.CreateTurnRequest request = request("查一下", "same-request");
        AgentV2Models.ScopeSnapshot scope = scope("ALL", Arrays.asList("p1", "p2"));
        when(fixture.scope.freeze(any(), any(), anyString())).thenReturn(scope);
        when(fixture.scope.attachConversationContext(any(), nullable(AgentV2Models.Task.class)))
                .thenReturn(scope);
        AgentV2Models.Task existing = task("task-1", "run-1", "same-request",
                fixture.service.requestFingerprint("查一下", scope, Collections.emptyMap(), Collections.emptyList()));
        when(fixture.v2.selectTaskByClientRequest("cid-1", "uid-1", "same-request")).thenReturn(existing);
        AgentRuntimeRecords.Run run = new AgentRuntimeRecords.Run();
        run.setRunId("run-1");
        run.setStatus("COMPLETED");
        when(fixture.runtime.selectRun("run-1")).thenReturn(run);
        AuthContext.set("uid-1", "cid-1");

        Map<String, Object> replay = fixture.service.createTurn("thread-1", request);

        assertEquals("task-1", replay.get("taskId"));
        assertEquals("run-1", replay.get("runId"));
        request.setMessage("改了问题");
        MyBizException conflict = assertThrows(MyBizException.class,
                () -> fixture.service.createTurn("thread-1", request));
        assertEquals("AGT409", conflict.getErrorCode());
    }

    @Test
    void duplicateInsertRaceReReadsCommittedTaskAndDoesNotCreateSecondRun() {
        CoordinatorFixture fixture = fixture();
        AgentV2Models.CreateTurnRequest request = request("查一下", "race-request");
        AgentV2Models.ScopeSnapshot scope = scope("ALL", Arrays.asList("p1", "p2"));
        when(fixture.scope.freeze(any(), any(), anyString())).thenReturn(scope);
        when(fixture.scope.attachConversationContext(any(), nullable(AgentV2Models.Task.class)))
                .thenReturn(scope);
        when(fixture.scope.apply(any(), any(), anyString())).thenReturn(fixture.workspace);
        when(fixture.context.build(any(), any(), nullable(AgentV2Models.Task.class), anyString(), any()))
                .thenReturn(new AgentV2Models.BoundedContext());
        String fingerprint = fixture.service.requestFingerprint("查一下", scope,
                Collections.emptyMap(), Collections.emptyList());
        AgentV2Models.Task concurrent = task("task-race", "run-race", "race-request", fingerprint);
        when(fixture.v2.selectTaskByClientRequest("cid-1", "uid-1", "race-request"))
                .thenReturn(null, concurrent);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate"))
                .when(fixture.v2).insertTask(any());
        AgentRuntimeRecords.Run existingRun = new AgentRuntimeRecords.Run();
        existingRun.setRunId("run-race");
        existingRun.setStatus("QUEUED");
        when(fixture.runtime.selectRun("run-race")).thenReturn(existingRun);
        AuthContext.set("uid-1", "cid-1");

        Map<String, Object> replay = fixture.service.createTurn("thread-1", request);

        assertEquals("task-race", replay.get("taskId"));
        verify(fixture.runtime, never()).insertRun(any());
    }

    @Test
    void committedReplayStillReturnsOriginalV2TaskAfterRolloutGateIsDisabled() {
        CoordinatorFixture fixture = fixture();
        AgentV2Models.CreateTurnRequest request = request("查一下", "replay-while-disabled");
        AgentV2Models.ScopeSnapshot scope = scope("ALL", Arrays.asList("p1", "p2"));
        when(fixture.scope.freeze(any(), any(), anyString())).thenReturn(scope);
        when(fixture.scope.attachConversationContext(any(), nullable(AgentV2Models.Task.class)))
                .thenReturn(scope);
        AgentV2Models.Task existing = task("task-existing", "run-existing", "replay-while-disabled",
                fixture.service.requestFingerprint("查一下", scope,
                        Collections.emptyMap(), Collections.emptyList()));
        when(fixture.v2.selectTaskByClientRequest("cid-1", "uid-1", "replay-while-disabled"))
                .thenReturn(existing);
        AgentRuntimeRecords.Run run = new AgentRuntimeRecords.Run();
        run.setRunId("run-existing");
        run.setStatus("QUEUED");
        when(fixture.runtime.selectRun("run-existing")).thenReturn(run);
        Map<String, Object> disabled = new LinkedHashMap<>();
        disabled.put("enabled", false);
        disabled.put("v2Enabled", false);
        when(fixture.manifest.manifest(isNull())).thenReturn(disabled);
        AuthContext.set("uid-1", "cid-1");

        Map<String, Object> replay = fixture.service.createTurn("thread-1", request);

        assertEquals("task-existing", replay.get("taskId"));
        verify(fixture.manifest, never()).manifest(isNull());
    }

    @Test
    void newSubmissionIsRejectedWhenRolloutGateIsDisabled() {
        CoordinatorFixture fixture = fixture();
        AgentV2Models.CreateTurnRequest request = request("查一下", "new-while-disabled");
        AgentV2Models.ScopeSnapshot scope = scope("ALL", Arrays.asList("p1", "p2"));
        when(fixture.scope.freeze(any(), any(), anyString())).thenReturn(scope);
        when(fixture.scope.attachConversationContext(any(), nullable(AgentV2Models.Task.class)))
                .thenReturn(scope);
        Map<String, Object> disabled = new LinkedHashMap<>();
        disabled.put("enabled", false);
        disabled.put("v2Enabled", false);
        when(fixture.manifest.manifest(isNull())).thenReturn(disabled);
        AuthContext.set("uid-1", "cid-1");

        MyBizException error = assertThrows(MyBizException.class,
                () -> fixture.service.createTurn("thread-1", request));

        assertEquals("AGT_V2_DISABLED", error.getErrorCode());
        verify(fixture.runtime, never()).insertRun(any());
    }

    private AgentV2Models.ScopeSnapshot scope(String mode, java.util.List<String> projectIds) {
        AgentV2Models.ScopeSnapshot scope = new AgentV2Models.ScopeSnapshot();
        scope.setCandidateRequestedSelectionMode(mode);
        scope.setCandidateProjectIds(projectIds);
        scope.setExplicitOverride(false);
        return scope;
    }

    private AgentV2CoordinatorService service() {
        return new AgentV2CoordinatorService(
                mock(AgentRuntimeMapper.class), mock(AgentV2Mapper.class), mock(AgentQuotaService.class),
                mock(AgentRunEventService.class), mock(AgentV2ScopeService.class),
                mock(AgentV2ContextBuilder.class), mock(AgentV2CapabilityManifestService.class),
                mock(AgentV2TaskStateService.class), mock(AgentV2RunExecutor.class),
                Runnable::run, new ObjectMapper());
    }

    private CoordinatorFixture fixture() {
        CoordinatorFixture fixture = new CoordinatorFixture();
        fixture.runtime = mock(AgentRuntimeMapper.class);
        fixture.v2 = mock(AgentV2Mapper.class);
        fixture.scope = mock(AgentV2ScopeService.class);
        fixture.context = mock(AgentV2ContextBuilder.class);
        fixture.manifest = mock(AgentV2CapabilityManifestService.class);
        Map<String, Object> enabled = new LinkedHashMap<>();
        enabled.put("enabled", true);
        enabled.put("v2Enabled", true);
        enabled.put("capabilities", Collections.emptyList());
        when(fixture.manifest.manifest(nullable(AgentRuntimeRecords.Workspace.class))).thenReturn(enabled);
        AgentRuntimeRecords.AgentThread thread = new AgentRuntimeRecords.AgentThread();
        thread.setThreadId("thread-1");
        thread.setWorkspaceId("workspace-1");
        thread.setOwnerUid("uid-1");
        thread.setStatus("ACTIVE");
        fixture.workspace = new AgentRuntimeRecords.Workspace();
        fixture.workspace.setWorkspaceId("workspace-1");
        fixture.workspace.setCid("cid-1");
        fixture.workspace.setScopeType("TENANT");
        fixture.workspace.setStatus("ACTIVE");
        when(fixture.runtime.selectThread("cid-1", "thread-1")).thenReturn(thread);
        when(fixture.runtime.selectWorkspaceById("cid-1", "workspace-1")).thenReturn(fixture.workspace);
        fixture.service = new AgentV2CoordinatorService(
                fixture.runtime, fixture.v2, mock(AgentQuotaService.class), mock(AgentRunEventService.class),
                fixture.scope, fixture.context, fixture.manifest, mock(AgentV2TaskStateService.class),
                mock(AgentV2RunExecutor.class),
                Runnable::run, new ObjectMapper());
        return fixture;
    }

    @Test
    void retryPreservesEmptyFrozenScopeAndRechecksAccessWithoutExpandingAll() throws Exception {
        CoordinatorFixture f=fixture();
        AgentV2Models.Task old=task("old","run-old","original", "hash");
        old.setCid("cid-1"); old.setOwnerUid("uid-1"); old.setStatus("BLOCKED");
        old.setVersion(3); old.setGoal("如何查看归还记录"); old.setContextJson("{}");
        AgentV2Models.ScopeSnapshot empty=scope("ALL", Collections.emptyList());
        empty.setFrozen(true); empty.setScopeHash("original-empty");
        old.setScopeJson(new ObjectMapper().writeValueAsString(empty));
        when(f.v2.selectTask("old")).thenReturn(old);
        when(f.scope.attachConversationContext(any(),any())).thenAnswer(call -> call.getArgument(0));
        when(f.scope.apply(any(),any(),anyString())).thenThrow(new MyBizException("access-check", "TEST"));
        AuthContext.set("uid-1","cid-1");
        Map<String,Object> input=new LinkedHashMap<>();
        input.put("clientRequestId","retry-new"); input.put("expectedTaskVersion",3);
        MyBizException stopped=assertThrows(MyBizException.class,()->f.service.retryTask("old",input));
        assertEquals("TEST",stopped.getErrorCode());
        org.mockito.ArgumentCaptor<AgentV2Models.ScopeSnapshot> captured=
                org.mockito.ArgumentCaptor.forClass(AgentV2Models.ScopeSnapshot.class);
        verify(f.scope).apply(any(),captured.capture(),anyString());
        assertEquals(Collections.emptyList(),captured.getValue().getCandidateProjectIds());
        assertEquals("original-empty",captured.getValue().getScopeHash());
        verify(f.scope,never()).freeze(any(),any(),anyString());
        verify(f.v2,never()).insertTask(any());
        assertEquals("BLOCKED",old.getStatus());
    }

    private AgentV2Models.CreateTurnRequest request(String message, String clientRequestId) {
        AgentV2Models.CreateTurnRequest request = new AgentV2Models.CreateTurnRequest();
        request.setMessage(message);
        request.setClientRequestId(clientRequestId);
        AgentV2Models.ScopeSelection selection = new AgentV2Models.ScopeSelection();
        selection.setSelectionMode("ALL");
        selection.setProjectIds(Collections.emptyList());
        request.setScopeSelection(selection);
        return request;
    }

    private AgentV2Models.Task task(String taskId,
                                    String runId,
                                    String clientRequestId,
                                    String fingerprint) {
        AgentV2Models.Task task = new AgentV2Models.Task();
        task.setTaskId(taskId);
        task.setTurnId("turn-1");
        task.setThreadId("thread-1");
        task.setWorkspaceId("workspace-1");
        task.setLatestRunId(runId);
        task.setClientRequestId(clientRequestId);
        task.setRequestFingerprint(fingerprint);
        task.setStatus("OPEN");
        task.setScopeJson("{}");
        return task;
    }

    private static class CoordinatorFixture {
        private AgentRuntimeMapper runtime;
        private AgentV2Mapper v2;
        private AgentV2ScopeService scope;
        private AgentV2ContextBuilder context;
        private AgentV2CapabilityManifestService manifest;
        private AgentRuntimeRecords.Workspace workspace;
        private AgentV2CoordinatorService service;
    }
}
