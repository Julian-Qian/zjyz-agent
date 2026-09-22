package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.attachment.AgentContractReviewService;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.service.*;
import com.zjyz.agent.workspace.tool.AgentRuntimeToolRegistry;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import com.zjyz.common.exception.MyBizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentV2DocumentReviewExecutionTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Answer<Object> writes = i -> i.getMethod().getReturnType() == int.class ? 1 : RETURNS_DEFAULTS.answer(i);
    private final AgentRuntimeMapper runtime = mock(AgentRuntimeMapper.class, writes);
    private final AgentV2Mapper tasks = mock(AgentV2Mapper.class, writes);
    private final AgentV2ConversationInterpreter interpreter = mock(AgentV2ConversationInterpreter.class);
    private final AgentV2ScopeService scopes = mock(AgentV2ScopeService.class);
    private final AgentV2CapabilityManifestService manifest = mock(AgentV2CapabilityManifestService.class);
    private final AgentV2ModelGateway model = mock(AgentV2ModelGateway.class);
    private final AgentRuntimeToolRegistry registry = mock(AgentRuntimeToolRegistry.class);
    private final AgentV2FinalizationService finalizer = mock(AgentV2FinalizationService.class);
    private final AgentContractReviewService reviews = mock(AgentContractReviewService.class);
    private final AgentV2Models.Task task = new AgentV2Models.Task();
    private final AgentRuntimeRecords.Run run = new AgentRuntimeRecords.Run();
    private final AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
    private final AgentV2Models.TaskSpec spec = new AgentV2Models.TaskSpec();
    private final List<Map<String,Object>> refs = List.of(Map.of("attachmentId","a1","parseRevision",1));
    private final AgentV2RunExecutor executor = new AgentV2RunExecutor(runtime,tasks,mock(AgentRunEventService.class),interpreter,
        scopes,manifest,model,registry,mock(AgentModelUsageService.class),finalizer,mock(AgentV2TaskStateService.class),json);

    private void setup(boolean mixed, boolean analysis, boolean inherit) throws Exception {
        task.setTaskId("t");task.setLatestRunId("r");task.setStatus("OPEN");task.setScopeJson("{}");task.setCapabilitySnapshotJson("{}");
        task.setContextJson(json.writeValueAsString(inherit ? Map.of("previousTask",Map.of("attachmentRefs",refs)) : Map.of("attachmentRefs",refs)));
        run.setRunId("r");run.setThreadId("th");run.setStatus("QUEUED");run.setCid("c");run.setOwnerUid("u");run.setWorkspaceId("w");
        when(tasks.selectTask("t")).thenReturn(task);when(runtime.selectRun("r")).thenReturn(run);
        spec.setTaskKinds(mixed ? List.of("DOCUMENT_REVIEW", analysis ? "ANALYSIS" : "QUERY") : List.of("DOCUMENT_REVIEW"));
        spec.setResolvedGoal(mixed ? "检查合同，并查询商城钢管信息作参考" : "检查合同付款条款");spec.setRiskLevel("READ");
        AgentV2Models.TaskRequirement doc = new AgentV2Models.TaskRequirement();doc.setRequirementId("r1");doc.setDescription("检查合同付款条款");
        List<AgentV2Models.TaskRequirement> requirements = new ArrayList<>();requirements.add(doc);
        if(mixed){AgentV2Models.TaskRequirement query=new AgentV2Models.TaskRequirement();query.setRequirementId("r2");query.setDescription("查询商城钢管信息");requirements.add(query);}
        spec.setRequirements(requirements);
        AgentV2Models.ConversationInterpretation interpretation = new AgentV2Models.ConversationInterpretation();
        interpretation.setDialogueAct("BUSINESS_QUERY");interpretation.setRelationType(inherit ? "CONTINUE" : "NEW");interpretation.setTaskSpec(spec);
        AgentV2Models.InterpretationResult interpreted=new AgentV2Models.InterpretationResult();interpreted.setSuccess(true);interpreted.setInterpretation(interpretation);
        when(interpreter.interpret(any(),any(),any(),anyString(),any(),any())).thenReturn(interpreted);
        AgentV2Models.ScopeSnapshot scope=new AgentV2Models.ScopeSnapshot();scope.setProjectIds(Collections.emptyList());
        when(scopes.resolveForRelation(any(),anyString())).thenReturn(scope);
        workspace.setCid("c");when(runtime.selectWorkspaceById("c","w")).thenReturn(workspace);when(scopes.apply(any(),any(),anyString())).thenReturn(workspace);
        when(manifest.manifest(workspace)).thenReturn(Map.of("capabilities",List.of(Map.of("code","market.search","available",true))));
        when(registry.modelDefinitions(workspace)).thenReturn(List.of(Map.of("type","function","function",Map.of("name","market_search","description","商城钢管","parameters",Map.of("type","object")))));
        when(registry.toolCode("market_search")).thenReturn("market.search");when(registry.riskLevel("market_search")).thenReturn("READ");when(registry.displayName(anyString())).thenReturn("商城查询");
        AgentSkillExecution queryResult=new AgentSkillExecution();queryResult.setAnswer("找到钢管信息");AgentEvidence evidence=new AgentEvidence();evidence.setToolCode("market.search");queryResult.setEvidence(evidence);
        when(registry.execute(eq("market_search"),anyString(),anyString(),eq(workspace))).thenReturn(queryResult);
        when(reviews.review(eq("th"),anyList(),anyString(),any(),any())).thenReturn(Map.of("completionStatus","FULL","reviewedBatches",1,"answer","合同审阅完成","attachmentRefs",refs,"findings",List.of()));
        ReflectionTestUtils.setField(executor,"contractReviewService",reviews);ReflectionTestUtils.setField(executor,"maxIterations",3);ReflectionTestUtils.setField(executor,"maxToolCalls",5);
    }

    @Test void duplicateReadCallsExecuteOnceAndKeepCompletion() throws Exception {
        setup(true,false,false);
        when(model.complete(anyList(),anyList(),eq("medium"))).thenReturn(
            calls(call("describe",AgentV2ToolDiscovery.DESCRIBE,"{\"names\":[\"document_contract_review\",\"market_search\"]}"),
                call("bind",AgentV2ToolDiscovery.BIND,"{\"requirements\":[{\"index\":0,\"capabilityCodes\":[\"document.contract_review\"],\"criteria\":{}},{\"index\":1,\"capabilityCodes\":[\"market.search\"],\"criteria\":{}}]}")),
            calls(call("doc","document_contract_review","{}"),call("query1","market_search","{}"),call("query2","market_search","{ }")),end());
        executor.execute("t","r");
        message("COMPLETED");
        verify(registry,times(1)).execute(eq("market_search"),anyString(),anyString(),eq(workspace));
    }

    @Test void pureContractProducesSatisfiedRequirementEvidence() throws Exception {
        setup(false,false,false);executor.execute("t","r");
        AgentRuntimeRecords.Message message=message("COMPLETED");
        assertEquals("FULL",json.readTree(message.getMetadataJson()).path("completionStatus").asText());
        assertEquals("SATISFIED",json.readTree(message.getMetadataJson()).path("requirementOutcomes").get(0).path("status").asText());
        verify(model,never()).complete(anyList(),anyList(),anyString());
    }

    @ParameterizedTest @CsvSource({"false,false","false,true","true,false"})
    void mixedReviewRunsAlongsideBusinessAndNeverOverridesFailure(boolean analysis,boolean failQuery) throws Exception {
        setup(true,analysis,false);
        if(failQuery)when(registry.execute(eq("market_search"),anyString(),anyString(),eq(workspace))).thenThrow(new MyBizException("查询失败","AGT500"));
        when(model.complete(anyList(),anyList(),eq("medium"))).thenReturn(
            calls(call("describe",AgentV2ToolDiscovery.DESCRIBE,"{\"names\":[\"document_contract_review\",\"market_search\"]}"),
                call("bind",AgentV2ToolDiscovery.BIND,"{\"requirements\":[{\"index\":0,\"capabilityCodes\":[\"document.contract_review\"],\"criteria\":{}},{\"index\":1,\"capabilityCodes\":[\"market.search\"],\"criteria\":{}}]}")),
            calls(call("review","document_contract_review","{}"),call("query","market_search","{}")),end());
        executor.execute("t","r");
        AgentRuntimeRecords.Message response=message(failQuery?"BLOCKED":"COMPLETED");
        assertEquals(failQuery?"PARTIAL":"FULL",json.readTree(response.getMetadataJson()).path("completionStatus").asText());
        assertEquals("SATISFIED",json.readTree(response.getMetadataJson()).path("requirementOutcomes").get(0).path("status").asText());
        assertEquals(failQuery?"UNSATISFIED":"SATISFIED",json.readTree(response.getMetadataJson()).path("requirementOutcomes").get(1).path("status").asText());
        verify(reviews).review(eq("th"),anyList(),eq(spec.getResolvedGoal()),any(),any());
        verify(registry).execute(eq("market_search"),anyString(),eq("{}"),eq(workspace));
        verify(registry,never()).execute(eq("document_contract_review"),anyString(),anyString(),any());
    }

    @Test void semanticRejectionIsReflectedInFinalRequirementOutcomes() throws Exception {
        setup(true,false,false);
        when(model.complete(anyList(),anyList(),eq("medium"))).thenReturn(
            calls(call("describe",AgentV2ToolDiscovery.DESCRIBE,"{\"names\":[\"document_contract_review\",\"market_search\"]}"),
                call("bind",AgentV2ToolDiscovery.BIND,"{\"requirements\":[{\"index\":0,\"capabilityCodes\":[\"document.contract_review\"],\"criteria\":{}},{\"index\":1,\"capabilityCodes\":[\"market.search\"],\"criteria\":{}}]}")),
            calls(call("review","document_contract_review","{}"),call("query","market_search","{}")),end());
        AgentRequirementVerifier verifier=mock(AgentRequirementVerifier.class);
        AgentRequirementVerifier.Result rejected=new AgentRequirementVerifier.Result();rejected.checked=true;rejected.rejected.add("r2");
        when(verifier.verify(anyString(),eq(spec),anyList(),any(),any())).thenReturn(rejected);
        ReflectionTestUtils.setField(executor,"requirementVerifier",verifier);
        executor.execute("t","r");
        com.fasterxml.jackson.databind.JsonNode metadata=json.readTree(message("BLOCKED").getMetadataJson());
        assertEquals("PARTIAL",metadata.path("completionStatus").asText());
        assertEquals("UNSATISFIED",metadata.path("requirementOutcomes").get(1).path("status").asText());
        assertTrue(metadata.path("requirementOutcomes").get(1).path("evidenceIds").isEmpty());
    }

    @Test void revokedLearningCannotLeaveOldContractCardsInMetadata() throws Exception {
        setup(false,false,false);
        com.zjyz.agent.workspace.learning.AgentLearningService learning=mock(com.zjyz.agent.workspace.learning.AgentLearningService.class);
        when(learning.context(anyString(),anyString(),nullable(List.class))).thenReturn(List.of(Map.of("id","rule","status","ACTIVE","kind","BUSINESS_RULE","version",1,"content","合同口径")));
        when(learning.stillValid(anyList())).thenReturn(false);
        ReflectionTestUtils.setField(executor,"learningService",learning);
        executor.execute("t","r");
        AgentRuntimeRecords.Message result=message("BLOCKED");
        com.fasterxml.jackson.databind.JsonNode metadata=json.readTree(result.getMetadataJson());
        assertTrue(metadata.path("documentReview").isEmpty());assertTrue(metadata.path("analysisClaims").isEmpty());
        assertEquals("NONE",metadata.path("completionStatus").asText());
        assertTrue(result.getContent().contains("更新或撤销"));
    }

    @Test void inheritedAttachmentsArePersistedForTheFollowingTurn() throws Exception {
        setup(false,false,true);executor.execute("t","r");
        verify(tasks).updateTaskContext(eq("t"),anyString(),any());
        assertEquals("a1",json.readTree(task.getContextJson()).path("attachmentRefs").get(0).path("attachmentId").asText());
    }

    @Test void partialContractEvidenceCannotCompleteAWholeDocumentRequirement(){
        AgentSkillExecution e=executor.contractReviewExecution(Map.of("completionStatus","PARTIAL","reviewedBatches",1,"answer","已审阅部分"));
        AgentV2Models.TaskRequirement requirement=new AgentV2Models.TaskRequirement();requirement.setDescription("全文审阅");requirement.setCapabilityCodes(List.of("document.contract_review"));
        AgentV2Models.TaskSpec s=new AgentV2Models.TaskSpec();s.setRequirements(List.of(requirement));
        assertFalse(AgentRequirementCoverage.missing(s,List.of(e)).isEmpty());
    }

    private AgentRuntimeRecords.Message message(String status){ArgumentCaptor<AgentRuntimeRecords.Message> response=ArgumentCaptor.forClass(AgentRuntimeRecords.Message.class);verify(finalizer).finalizeRun(eq(task),eq(run),response.capture(),anyList(),eq(status),anyString(),any());return response.getValue();}
    private static AgentModelGateway.ToolCall call(String id,String name,String args){AgentModelGateway.ToolCall c=new AgentModelGateway.ToolCall();c.setId(id);c.setName(name);c.setArguments(args);return c;}
    private static AgentModelGateway.ModelResult calls(AgentModelGateway.ToolCall... calls){AgentModelGateway.ModelResult r=new AgentModelGateway.ModelResult();r.setSuccess(true);r.setToolCalls(Arrays.asList(calls));return r;}
    private static AgentModelGateway.ModelResult end(){AgentModelGateway.ModelResult r=new AgentModelGateway.ModelResult();r.setSuccess(true);r.setContent("完成");return r;}
}
