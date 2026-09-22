package com.zjyz.agent.workspace.v2.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.service.*;
import com.zjyz.agent.workspace.tool.AgentRuntimeToolRegistry;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import com.zjyz.agent.workspace.knowledge.AgentGuidanceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class AgentV2TaskPathsTest {
 @Test void guidanceDoesNotDiscoverOrQueryBusinessAndReportsMissingSecondRequirement() throws Exception {
  Fixture f=new Fixture();AgentGuidanceService guidance=mock(AgentGuidanceService.class);
  ReflectionTestUtils.setField(f.executor,"guidanceService",guidance);
  f.spec.setTaskKinds(List.of("GUIDANCE"));
  f.spec.setRequirements(new ArrayList<>(List.of(requirement("r1","查看归还"),requirement("r2","未知操作"))));
  AgentSkillExecution found=new AgentSkillExecution();found.setConfidence(1d);found.setAnswer("项目管理→归还单");
  AgentEvidence e=new AgentEvidence();e.setToolCode("help.search");e.setRecordCount(1);found.setEvidence(e);
  when(guidance.answer(eq("查看归还"),any(),any(),any())).thenReturn(found);
  f.executor.execute("t","r");
  verifyNoInteractions(f.model,f.registry);
  ArgumentCaptor<AgentRuntimeRecords.Message> answer=ArgumentCaptor.forClass(AgentRuntimeRecords.Message.class);
  verify(f.finalizer).finalizeRun(any(),any(),answer.capture(),anyList(),eq("BLOCKED"),anyString(),any());
  assertTrue(answer.getValue().getContent().contains("未知操作"));
  com.fasterxml.jackson.databind.JsonNode meta=f.json.readTree(answer.getValue().getMetadataJson());
  assertEquals("PARTIAL",meta.path("completionStatus").asText());
  assertEquals("SATISFIED",meta.path("requirementOutcomes").get(0).path("status").asText());
  assertEquals("UNSATISFIED",meta.path("requirementOutcomes").get(1).path("status").asText());
 }
 @Test void noKnowledgeIsNotSuccessfulGuidance() {
  Fixture f=new Fixture();AgentGuidanceService guidance=mock(AgentGuidanceService.class);
  ReflectionTestUtils.setField(f.executor,"guidanceService",guidance);f.spec.setTaskKinds(List.of("GUIDANCE"));
  f.executor.execute("t","r");
  verify(f.finalizer).finalizeRun(any(),any(),any(),anyList(),eq("BLOCKED"),anyString(),any());verifyNoInteractions(f.model,f.registry);
 }
 private static AgentV2Models.TaskRequirement requirement(String id,String goal){AgentV2Models.TaskRequirement r=new AgentV2Models.TaskRequirement();r.setRequirementId(id);r.setDescription(goal);return r;}
 private static class Fixture {
  ObjectMapper json=new ObjectMapper();AgentV2ModelGateway model=mock(AgentV2ModelGateway.class);AgentRuntimeToolRegistry registry=mock(AgentRuntimeToolRegistry.class);
  AgentV2FinalizationService finalizer=mock(AgentV2FinalizationService.class);AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();AgentV2RunExecutor executor;
  Fixture(){
   Answer<Object> writes=i->i.getMethod().getReturnType()==int.class?1:RETURNS_DEFAULTS.answer(i);
   AgentRuntimeMapper runtime=mock(AgentRuntimeMapper.class,writes);AgentV2Mapper tasks=mock(AgentV2Mapper.class,writes);
   AgentV2ConversationInterpreter interpreter=mock(AgentV2ConversationInterpreter.class);AgentV2ScopeService scopes=mock(AgentV2ScopeService.class);
   AgentV2CapabilityManifestService manifest=mock(AgentV2CapabilityManifestService.class);
   AgentV2Models.Task task=new AgentV2Models.Task();task.setTaskId("t");task.setLatestRunId("r");task.setStatus("OPEN");task.setContextJson("{}");task.setScopeJson("{}");task.setCapabilitySnapshotJson("{}");
   AgentRuntimeRecords.Run run=new AgentRuntimeRecords.Run();run.setRunId("r");run.setStatus("QUEUED");run.setCid("c");run.setOwnerUid("u");run.setWorkspaceId("w");
   when(tasks.selectTask("t")).thenReturn(task);when(runtime.selectRun("r")).thenReturn(run);
   spec.setResolvedGoal("使用指导");spec.setRiskLevel("READ");
   spec.setRequirements(new ArrayList<>(List.of(requirement("r1","使用指导"))));
   AgentV2Models.ConversationInterpretation i=new AgentV2Models.ConversationInterpretation();i.setDialogueAct("BUSINESS_QUERY");i.setRelationType("NEW");i.setTaskSpec(spec);
   AgentV2Models.InterpretationResult understood=new AgentV2Models.InterpretationResult();understood.setSuccess(true);understood.setInterpretation(i);
   when(interpreter.interpret(any(),any(),any(),anyString(),any(),any())).thenReturn(understood);
   AgentV2Models.ScopeSnapshot scope=new AgentV2Models.ScopeSnapshot();when(scopes.resolveForRelation(any(),anyString())).thenReturn(scope);
   AgentRuntimeRecords.Workspace w=new AgentRuntimeRecords.Workspace();w.setCid("c");when(runtime.selectWorkspaceById("c","w")).thenReturn(w);when(scopes.apply(any(),any(),anyString())).thenReturn(w);
   when(manifest.manifest(w)).thenReturn(Map.of("capabilities",List.of(Map.of("code","help.search","available",true))));
   executor=new AgentV2RunExecutor(runtime,tasks,mock(AgentRunEventService.class),interpreter,scopes,manifest,model,registry,mock(AgentModelUsageService.class),finalizer,mock(AgentV2TaskStateService.class),json);
  }
 }
}
