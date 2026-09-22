package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.service.AgentModelUsageService;
import com.zjyz.agent.workspace.service.AgentRunEventService;
import com.zjyz.agent.workspace.tool.AgentRuntimeToolRegistry;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentV2MaterialMetricExecutionTest {
    @ParameterizedTest
    @CsvSource({"今年帮我赚到最多钱的材料是什么,WAITING_USER", "材料租金收入最高的是哪个,BLOCKED",
            "材料利润排行,BLOCKED", "材料回款排行,BLOCKED"})
    void wrongQuantityInterpretationCannotReachPlannerOrPublishRanking(String question, String expectedStatus) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Answer<Object> successfulWrites = invocation -> invocation.getMethod().getReturnType() == int.class
                ? 1 : RETURNS_DEFAULTS.answer(invocation);
        AgentRuntimeMapper runtime = mock(AgentRuntimeMapper.class, successfulWrites);
        AgentV2Mapper tasks = mock(AgentV2Mapper.class, successfulWrites);
        AgentV2ConversationInterpreter interpreter = mock(AgentV2ConversationInterpreter.class);
        AgentV2ScopeService scopes = mock(AgentV2ScopeService.class);
        AgentV2CapabilityManifestService manifests = mock(AgentV2CapabilityManifestService.class);
        AgentV2ModelGateway planner = mock(AgentV2ModelGateway.class);
        AgentRuntimeToolRegistry registry = mock(AgentRuntimeToolRegistry.class);
        AgentV2FinalizationService finalizer = mock(AgentV2FinalizationService.class);
        AgentV2TaskStateService taskState = mock(AgentV2TaskStateService.class);

        AgentV2Models.BoundedContext context = new AgentV2Models.BoundedContext();
        AgentV2Models.ContextMessage message = new AgentV2Models.ContextMessage();
        message.setRole("user");
        message.setContent(question);
        context.setMessages(Collections.singletonList(message));
        AgentV2Models.Task task = new AgentV2Models.Task();
        task.setTaskId("task-1");
        task.setLatestRunId("run-1");
        task.setStatus("OPEN");
        task.setContextJson(json.writeValueAsString(context));
        task.setScopeJson("{}");
        task.setCapabilitySnapshotJson("{}");
        AgentRuntimeRecords.Run run = new AgentRuntimeRecords.Run();
        run.setRunId("run-1");
        run.setStatus("QUEUED");
        run.setCid("cid-1");
        run.setOwnerUid("uid-1");
        run.setWorkspaceId("workspace-1");
        when(tasks.selectTask("task-1")).thenReturn(task);
        when(runtime.selectRun("run-1")).thenReturn(run);

        AgentV2Models.TaskSpec spec = new AgentV2Models.TaskSpec();
        spec.setResolvedGoal("今年租出最多的材料是什么");
        spec.setAnalysisTarget("MATERIAL");
        spec.setRequestedMetrics(Collections.singletonList("QUANTITY"));
        spec.setRiskLevel("READ");
        AgentV2Models.ConversationInterpretation interpretation = new AgentV2Models.ConversationInterpretation();
        interpretation.setDialogueAct("BUSINESS_QUERY");
        interpretation.setRelationType("NEW");
        interpretation.setTaskSpec(spec);
        AgentV2Models.InterpretationResult result = new AgentV2Models.InterpretationResult();
        result.setSuccess(true);
        result.setInterpretation(interpretation);
        when(interpreter.interpret(any(), any(), any(), anyString(), any(), any())).thenReturn(result);
        AgentV2Models.ScopeSnapshot scope = new AgentV2Models.ScopeSnapshot();
        scope.setProjectIds(Collections.singletonList("p-1"));
        when(scopes.resolveForRelation(any(), anyString())).thenReturn(scope);
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("cid-1");
        when(runtime.selectWorkspaceById("cid-1", "workspace-1")).thenReturn(workspace);
        when(scopes.apply(any(), any(), anyString())).thenReturn(workspace);
        when(manifests.manifest(workspace)).thenReturn(Collections.emptyMap());

        AgentV2RunExecutor executor = new AgentV2RunExecutor(runtime, tasks, mock(AgentRunEventService.class),
                interpreter, scopes, manifests, planner, registry, mock(AgentModelUsageService.class),
                finalizer, taskState, json);
        executor.execute("task-1", "run-1");

        ArgumentCaptor<AgentRuntimeRecords.Message> answer = ArgumentCaptor.forClass(AgentRuntimeRecords.Message.class);
        verify(finalizer).finalizeRun(eq(task), eq(run), answer.capture(), eq(Collections.emptyList()),
                eq(expectedStatus), anyString(), any());
        assertEquals(0, json.readTree(answer.getValue().getMetadataJson()).path("cards").size());
        assertEquals(0, json.readTree(answer.getValue().getMetadataJson()).path("usedTools").size());
        assertFalse(answer.getValue().getContent().contains("按单位"));
        verifyNoInteractions(planner, registry);
        assertEquals(question, task.getGoal());
        assertFalse(json.readTree(task.getTaskSpecJson()).path("requestedMetrics").toString().contains("QUANTITY"));
        if ("WAITING_USER".equals(expectedStatus)) {
            verify(taskState).persistClarification(eq(task), any());
            assertTrue(answer.getValue().getContent().contains("是指租金收入"));
        } else {
            verifyNoInteractions(taskState);
            assertTrue(answer.getValue().getContent().contains("尚不支持"));
        }
    }
}
