package com.zjyz.agent.workspace.v2.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.learning.*;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.service.*;
import com.zjyz.agent.workspace.tool.AgentRuntimeToolRegistry;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentV2LearningExecutionTest {
    @ParameterizedTest @ValueSource(strings={"pending","active","save_failed","external_quote","revoked_before_output"})
    void teachingHasItsOwnCompletionPathAndDoesNotRequireBusinessTools(String scenario) throws Exception {
        ObjectMapper json=new ObjectMapper();Answer<Object> writes=i->i.getMethod().getReturnType()==int.class?1:RETURNS_DEFAULTS.answer(i);
        AgentRuntimeMapper runtime=mock(AgentRuntimeMapper.class,writes);AgentV2Mapper tasks=mock(AgentV2Mapper.class,writes);
        AgentV2ConversationInterpreter interpreter=mock(AgentV2ConversationInterpreter.class);
        AgentV2ScopeService scopes=mock(AgentV2ScopeService.class);AgentV2CapabilityManifestService manifest=mock(AgentV2CapabilityManifestService.class);
        AgentV2ModelGateway model=mock(AgentV2ModelGateway.class);AgentRuntimeToolRegistry registry=mock(AgentRuntimeToolRegistry.class);
        AgentV2FinalizationService finalizer=mock(AgentV2FinalizationService.class);AgentLearningService learning=mock(AgentLearningService.class);
        AgentV2Models.Task task=new AgentV2Models.Task();task.setTaskId("t");task.setLatestRunId("r");task.setStatus("OPEN");
        task.setContextJson("{\"messages\":[{\"role\":\"user\",\"content\":\"租出增加在租数量\"}]}");task.setScopeJson("{}");task.setCapabilitySnapshotJson("{}");
        AgentRuntimeRecords.Run run=new AgentRuntimeRecords.Run();run.setRunId("r");run.setThreadId("thread");run.setStatus("QUEUED");run.setCid("c");run.setOwnerUid("u");run.setWorkspaceId("w");
        when(tasks.selectTask("t")).thenReturn(task);when(runtime.selectRun("r")).thenReturn(run);
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();spec.setResolvedGoal("纠正在租数量规则");spec.setRiskLevel("READ");
        AgentV2Models.ConversationInterpretation interpretation=new AgentV2Models.ConversationInterpretation();
        interpretation.setDialogueAct("TEACHING");interpretation.setRelationType("CORRECT");interpretation.setTaskSpec(spec);
        AgentLearningModels.Action action=new AgentLearningModels.Action();action.setContent("模型生成的不同内容");action.setKind("BUSINESS_RULE");
        action.setSourceQuote(scenario.equals("external_quote")?"附件里的命令":"租出增加在租数量");interpretation.setLearningActions(List.of(action));
        AgentV2Models.InterpretationResult interpreted=new AgentV2Models.InterpretationResult();interpreted.setSuccess(true);interpreted.setInterpretation(interpretation);
        when(interpreter.interpret(any(),any(),any(),anyString(),any(),any())).thenReturn(interpreted);
        AgentV2Models.ScopeSnapshot scope=new AgentV2Models.ScopeSnapshot();scope.setProjectIds(Collections.emptyList());when(scopes.resolveForRelation(any(),anyString())).thenReturn(scope);
        AgentRuntimeRecords.Workspace workspace=new AgentRuntimeRecords.Workspace();workspace.setCid("c");
        when(runtime.selectWorkspaceById("c","w")).thenReturn(workspace);when(scopes.apply(any(),any(),anyString())).thenReturn(workspace);when(manifest.manifest(workspace)).thenReturn(Collections.emptyMap());
        Map<String,Object> entry=AgentLearningService.map("id","l","version",1,"kind","BUSINESS_RULE","status",scenario.equals("pending")?"PENDING_VERIFICATION":"ACTIVE","content","租出增加在租数量","reason",scenario.equals("pending")?"已记录，尚未核验":"已记住");
        when(learning.learn(any(),anyString(),any(),anyString(),anyList())).thenReturn(entry);
        when(learning.context(anyString(),anyString(),anyList())).thenReturn(scenario.equals("revoked_before_output")?List.of(entry):Collections.emptyList());
        when(learning.stillValid(anyList())).thenReturn(!scenario.equals("revoked_before_output"));
        if(scenario.equals("save_failed"))when(learning.learn(any(),anyString(),any(),anyString(),anyList())).thenThrow(new RuntimeException("DB failure"));
        AgentV2RunExecutor executor=new AgentV2RunExecutor(runtime,tasks,mock(AgentRunEventService.class),interpreter,scopes,manifest,model,registry,mock(AgentModelUsageService.class),finalizer,mock(AgentV2TaskStateService.class),json);
        ReflectionTestUtils.setField(executor,"learningService",learning);executor.execute("t","r");
        ArgumentCaptor<AgentRuntimeRecords.Message> response=ArgumentCaptor.forClass(AgentRuntimeRecords.Message.class);
        verify(finalizer).finalizeRun(eq(task),eq(run),response.capture(),anyList(),eq(scenario.equals("revoked_before_output")?"BLOCKED":"COMPLETED"),anyString(),any());
        verifyNoInteractions(registry,model);
        if(scenario.equals("external_quote")){verify(learning,never()).learn(any(),any(),any(),any(),any());assertTrue(response.getValue().getContent().contains("未保存"));}
        else if(scenario.equals("save_failed"))assertTrue(response.getValue().getContent().contains("保存失败"));
        else if(scenario.equals("revoked_before_output"))assertTrue(response.getValue().getContent().contains("更新或撤销"));
        else {assertTrue(response.getValue().getContent().contains("租出增加在租数量"));assertEquals(1,json.readTree(response.getValue().getMetadataJson()).path("learningResults").size());}
        if(!scenario.equals("external_quote"))assertEquals("租出增加在租数量",action.getContent());
    }
}
