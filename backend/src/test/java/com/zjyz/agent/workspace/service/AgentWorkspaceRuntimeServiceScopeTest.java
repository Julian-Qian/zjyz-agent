package com.zjyz.agent.workspace.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.service.AgentQuotaService;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeManagementService;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.model.AgentWorkspaceRequests;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.FeatureAccountWhitelistMapper;
import com.zjyz.dao.ProjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkspaceRuntimeServiceScopeTest {

    @Test
    void tenantRunRejectsMissingOrUnknownSelectionMode() {
        AgentWorkspaceRuntimeService service = service();

        assertAg400(() -> service.resolveProjectScope(request(null, Collections.emptyList()), tenant(), "cid-1"));
        assertAg400(() -> service.resolveProjectScope(request("  ", Collections.emptyList()), tenant(), "cid-1"));
        assertAg400(() -> service.resolveProjectScope(request("SINGLE", Collections.emptyList()), tenant(), "cid-1"));
    }

    @Test
    void allSelectionRejectsAnyProjectIds() {
        AgentWorkspaceRuntimeService service = service();

        assertAg400(() -> service.resolveProjectScope(
                request("ALL", Collections.singletonList("project-1")), tenant(), "cid-1"));
    }

    @Test
    void allSelectionWithEmptyProjectIdsIsPreserved() {
        Map<String, Object> scope = service().resolveProjectScope(
                request("ALL", Collections.emptyList()), tenant(), "cid-1");

        assertEquals("ALL", scope.get("selectionMode"));
        assertEquals(Collections.emptyList(), scope.get("projectIds"));
    }

    @Test
    void idempotentReplayRequiresSameThreadMessageAndScope() throws Exception {
        AgentRuntimeMapper mapper = mock(AgentRuntimeMapper.class);
        AgentWorkspaceRuntimeService service = service(mapper);
        AgentRuntimeRecords.Run run = new AgentRuntimeRecords.Run();
        run.setRunId("run-1");
        run.setCid("cid-1");
        run.setThreadId("thread-1");
        run.setWorkspaceId("workspace-1");
        AgentRuntimeRecords.Message message = new AgentRuntimeRecords.Message();
        message.setContent("查当前库存");
        message.setMetadataJson("{\"projectScope\":{\"selectionMode\":\"ALL\",\"projectIds\":[]}}");
        when(mapper.selectRunUserMessage("cid-1", "thread-1", "run-1")).thenReturn(message);
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("selectionMode", "ALL");
        scope.put("projectIds", Collections.emptyList());

        service.requireSameIdempotentRequest(run, "thread-1", "workspace-1", "查当前库存", scope);

        MyBizException changedMessage = assertThrows(MyBizException.class,
                () -> service.requireSameIdempotentRequest(
                        run, "thread-1", "workspace-1", "查合同", scope));
        MyBizException changedThread = assertThrows(MyBizException.class,
                () -> service.requireSameIdempotentRequest(
                        run, "thread-2", "workspace-1", "查当前库存", scope));
        Map<String, Object> changedScope = new LinkedHashMap<>();
        changedScope.put("selectionMode", "EXPLICIT");
        changedScope.put("projectIds", Collections.singletonList("project-1"));
        MyBizException changedProjectScope = assertThrows(MyBizException.class,
                () -> service.requireSameIdempotentRequest(
                        run, "thread-1", "workspace-1", "查当前库存", changedScope));
        assertEquals("AGT409", changedMessage.getErrorCode());
        assertEquals("AGT409", changedThread.getErrorCode());
        assertEquals("AGT409", changedProjectScope.getErrorCode());
    }

    private AgentWorkspaceRuntimeService service() {
        return service(mock(AgentRuntimeMapper.class));
    }

    private AgentWorkspaceRuntimeService service(AgentRuntimeMapper mapper) {
        return new AgentWorkspaceRuntimeService(
                mapper,
                mock(ProjectMapper.class),
                mock(FeatureAccountWhitelistMapper.class),
                mock(AgentQuotaService.class),
                mock(AgentRunEventService.class),
                mock(AgentRunExecutor.class),
                Runnable::run,
                new ObjectMapper(),
                mock(AgentKnowledgeManagementService.class));
    }

    private AgentRuntimeRecords.Workspace tenant() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("cid-1");
        workspace.setScopeType("TENANT");
        return workspace;
    }

    private AgentWorkspaceRequests.CreateRun request(String mode, java.util.List<String> projectIds) {
        AgentWorkspaceRequests.CreateRun request = new AgentWorkspaceRequests.CreateRun();
        request.setSelectionMode(mode);
        request.setProjectIds(projectIds);
        return request;
    }

    private void assertAg400(org.junit.jupiter.api.function.Executable executable) {
        MyBizException error = assertThrows(MyBizException.class, executable);
        assertEquals("AGT400", error.getErrorCode());
    }
}
