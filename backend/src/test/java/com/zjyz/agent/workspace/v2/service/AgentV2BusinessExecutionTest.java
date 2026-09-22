package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.service.*;
import com.zjyz.agent.workspace.tool.AgentRuntimeToolRegistry;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import com.zjyz.common.exception.MyBizException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentV2BusinessExecutionTest {
    @ParameterizedTest @CsvSource({"false,false", "true,false", "false,true"})
    void noProjectsCanQueryMarketAndLaterFailureCannotBeMarkedComplete(boolean failSecondStep, boolean skipBinding) throws Exception {
        ObjectMapper json=new ObjectMapper();
        Answer<Object> writes=i->i.getMethod().getReturnType()==int.class?1:RETURNS_DEFAULTS.answer(i);
        AgentRuntimeMapper runtime=mock(AgentRuntimeMapper.class,writes);AgentV2Mapper tasks=mock(AgentV2Mapper.class,writes);
        AgentV2ConversationInterpreter interpreter=mock(AgentV2ConversationInterpreter.class);
        AgentV2ScopeService scopes=mock(AgentV2ScopeService.class);AgentV2CapabilityManifestService manifest=mock(AgentV2CapabilityManifestService.class);
        AgentV2ModelGateway model=mock(AgentV2ModelGateway.class);AgentRuntimeToolRegistry registry=mock(AgentRuntimeToolRegistry.class);
        AgentV2FinalizationService finalizer=mock(AgentV2FinalizationService.class);
        AgentV2Models.Task task=new AgentV2Models.Task();task.setTaskId("t1");task.setLatestRunId("r1");task.setStatus("OPEN");
        task.setContextJson("{}");task.setScopeJson("{}");task.setCapabilitySnapshotJson("{}");
        AgentRuntimeRecords.Run run=new AgentRuntimeRecords.Run();run.setRunId("r1");run.setStatus("QUEUED");run.setCid("c1");run.setOwnerUid("u1");run.setWorkspaceId("w1");
        when(tasks.selectTask("t1")).thenReturn(task);when(runtime.selectRun("r1")).thenReturn(run);
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();spec.setResolvedGoal("去商城找钢管");spec.setRiskLevel("READ");
        AgentV2Models.TaskRequirement requirement=new AgentV2Models.TaskRequirement();
        requirement.setDescription("去商城找钢管");spec.setRequirements(new ArrayList<>(List.of(requirement)));
        AgentV2Models.ConversationInterpretation interpretation=new AgentV2Models.ConversationInterpretation();
        interpretation.setDialogueAct("BUSINESS_QUERY");interpretation.setRelationType("NEW");interpretation.setTaskSpec(spec);
        AgentV2Models.InterpretationResult interpreted=new AgentV2Models.InterpretationResult();interpreted.setSuccess(true);interpreted.setInterpretation(interpretation);
        when(interpreter.interpret(any(),any(),any(),anyString(),any(),any())).thenReturn(interpreted);
        AgentV2Models.ScopeSnapshot scope=new AgentV2Models.ScopeSnapshot();scope.setProjectIds(Collections.emptyList());
        when(scopes.resolveForRelation(any(),anyString())).thenReturn(scope);
        AgentRuntimeRecords.Workspace workspace=new AgentRuntimeRecords.Workspace();workspace.setCid("c1");
        when(runtime.selectWorkspaceById("c1","w1")).thenReturn(workspace);when(scopes.apply(any(),any(),anyString())).thenReturn(workspace);
        when(manifest.manifest(workspace)).thenReturn(Map.of("capabilities",Arrays.asList(
                Map.of("code","market.search","available",true),Map.of("code","inventory.materials","available",true))));
        when(registry.toolCode("market_search")).thenReturn("market.search");when(registry.toolCode("inventory_materials")).thenReturn("inventory.materials");
        when(registry.riskLevel(anyString())).thenReturn("READ");when(registry.displayName(anyString())).thenReturn("材料查询");
        AgentSkillExecution success=new AgentSkillExecution();success.setAnswer("找到公开钢管信息");
        AgentEvidence evidence=new AgentEvidence();evidence.setToolCode("market.search");success.setEvidence(evidence);
        success.setCards(Collections.singletonList(Map.of("type","business-records","title","商城结果")));
        when(registry.execute(eq("market_search"),anyString(),anyString(),eq(workspace))).thenReturn(success);
        when(registry.execute(eq("inventory_materials"),anyString(),anyString(),eq(workspace))).thenThrow(new MyBizException("库存查询暂时失败","AGT500"));
        AgentModelGateway.ModelResult first=new AgentModelGateway.ModelResult();first.setSuccess(true);
        AgentModelGateway.ToolCall call=new AgentModelGateway.ToolCall();call.setId("call1");call.setName("market_search");call.setArguments("{}");
        first.setToolCalls(new ArrayList<>(List.of(call)));
        if(failSecondStep) {AgentModelGateway.ToolCall second=new AgentModelGateway.ToolCall();second.setId("call2");second.setName("inventory_materials");second.setArguments("{}");first.getToolCalls().add(second);}
        AgentModelGateway.ModelResult end=new AgentModelGateway.ModelResult();end.setSuccess(true);end.setContent("查询完成");
        when(registry.modelDefinitions(workspace)).thenReturn(List.of(
                Map.of("type","function","function",Map.of("name","market_search","description","商城找材料","parameters",Map.of("type","object"))),
                Map.of("type","function","function",Map.of("name","inventory_materials","description","库存材料","parameters",Map.of("type","object")))));
        AgentModelGateway.ModelResult discover = new AgentModelGateway.ModelResult(); discover.setSuccess(true);
        AgentModelGateway.ToolCall describe = new AgentModelGateway.ToolCall();describe.setId("discover");describe.setName(AgentV2ToolDiscovery.DESCRIBE);
        describe.setArguments("{\"names\":[\"market_search\",\"inventory_materials\"]}");AgentModelGateway.ToolCall bind=new AgentModelGateway.ToolCall();bind.setId("bind");bind.setName(AgentV2ToolDiscovery.BIND);
        bind.setArguments("{\"requirements\":[{\"index\":0,\"capabilityCodes\":[\"market.search\"],\"criteria\":{}}]}");
        discover.setToolCalls(skipBinding ? List.of(describe) : List.of(describe,bind));
        when(model.complete(anyList(),anyList(),eq("medium"))).thenReturn(discover,first,end);
        AgentV2RunExecutor executor=new AgentV2RunExecutor(runtime,tasks,mock(AgentRunEventService.class),interpreter,scopes,manifest,model,registry,
                mock(AgentModelUsageService.class),finalizer,mock(AgentV2TaskStateService.class),json);
        ReflectionTestUtils.setField(executor,"maxIterations",3);ReflectionTestUtils.setField(executor,"maxToolCalls",4);
        executor.execute("t1","r1");
        if (skipBinding) {
            verify(registry,never()).execute(anyString(),anyString(),anyString(),any());
            verify(finalizer).finalizeRun(eq(task),eq(run),any(),anyList(),eq("BLOCKED"),anyString(),any());
            return;
        }
        verify(registry).execute(eq("market_search"),anyString(),eq("{}"),eq(workspace));
        ArgumentCaptor<AgentRuntimeRecords.Message> response=ArgumentCaptor.forClass(AgentRuntimeRecords.Message.class);
        verify(finalizer).finalizeRun(eq(task),eq(run),response.capture(),anyList(),eq(failSecondStep?"BLOCKED":"COMPLETED"),anyString(),any());
        assertTrue(response.getValue().getContent().contains("找到公开钢管信息"));
        if(failSecondStep) assertTrue(response.getValue().getContent().contains("部分查询"));
        assertEquals(List.of("market.search"),spec.getRequirements().get(0).getCapabilityCodes());
        verify(tasks,atLeast(2)).updateInterpretation(eq("t1"),eq("READY"),any(),any(),any(),any(),any(),any(),any());
        assertEquals(1,json.readTree(response.getValue().getMetadataJson()).path("cards").size());
    }
}
