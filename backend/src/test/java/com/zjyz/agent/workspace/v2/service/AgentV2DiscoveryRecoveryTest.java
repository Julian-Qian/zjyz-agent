package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentV2DiscoveryRecoveryTest {
    private final ObjectMapper json=new ObjectMapper();
    private AgentV2Models.TaskSpec spec() {
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();spec.setResolvedGoal("找杭州钢管");
        AgentV2Models.TaskRequirement r=new AgentV2Models.TaskRequirement();r.setDescription("找杭州钢管");r.setCriteria(Map.of("city","杭州"));
        spec.setRequirements(new ArrayList<>(List.of(r)));return spec;
    }
    private Map<String,Object> definition(String name,String description) {
        return Map.of("type","function","function",Map.of("name",name,"description",description,"parameters",Map.of("type","object")));
    }
    private AgentV2ToolDiscovery discovery(AgentV2Models.TaskSpec spec) {
        return new AgentV2ToolDiscovery(List.of(definition("market_search","商城城市材料搜索"),definition("inventory_materials","企业库存材料查询")),
                n->n.equals("market_search")?"market.search":"inventory.materials",spec,json);
    }
    @Test void onlyBridgeToolsAreLoadedInitiallyAndUnknownNamesNeverGainAccess() {
        AgentV2ToolDiscovery d=discovery(spec());assertEquals(3,d.tools().size());
        assertFalse(d.isLoaded("market_search"));
        Object answer=d.handle(AgentV2ToolDiscovery.DESCRIBE,"{\"names\":[\"market_search\",\"admin_delete\"]}");
        assertTrue(answer.toString().contains("admin_delete"));assertTrue(d.isLoaded("market_search"));assertFalse(d.isLoaded("admin_delete"));assertEquals(4,d.tools().size());
    }
    @Test void searchMissProvidesDomainsAndEmptyQueryCanBroadenSearch() {
        AgentV2ToolDiscovery d=discovery(spec());
        Map<?,?> miss=(Map<?,?>)d.handle(AgentV2ToolDiscovery.SEARCH,"{\"query\":\"zzzzzz\"}");
        assertEquals(0,miss.get("total"));assertTrue(miss.get("domains").toString().contains("market"));
        Map<?,?> all=(Map<?,?>)d.handle(AgentV2ToolDiscovery.SEARCH,"{\"query\":\"\"}");assertEquals(2,all.get("total"));
        Map<?,?> selected=(Map<?,?>)d.handle(AgentV2ToolDiscovery.SEARCH,"{\"query\":\"库存\"}");assertEquals(1,selected.get("total"));
    }
    @Test void bindingIsAtomicAndCannotDropUserFiltersOrUseUndiscoveredTools() {
        AgentV2Models.TaskSpec spec=spec();AgentV2ToolDiscovery d=discovery(spec);
        assertFalse(d.isBound());
        String binding="{\"requirements\":[{\"index\":0,\"capabilityCodes\":[\"market.search\"],\"criteria\":{\"city\":\"杭州\"}}]}";
        assertTrue(d.handle(AgentV2ToolDiscovery.BIND,binding).toString().contains("UNDISCOVERED"));
        d.handle(AgentV2ToolDiscovery.DESCRIBE,"{\"names\":[\"market_search\"]}");
        assertTrue(d.handle(AgentV2ToolDiscovery.BIND,binding.replace("杭州","上海")).toString().contains("ORIGINAL_CRITERIA_CHANGED"));
        assertTrue(spec.getRequirements().get(0).getCapabilityCodes().isEmpty());
        assertFalse(d.handle(AgentV2ToolDiscovery.BIND,binding).toString().contains("error"));
        assertTrue(d.isBound());
        assertEquals(List.of("market.search"),spec.getRequirements().get(0).getCapabilityCodes());
        assertEquals("找杭州钢管",spec.getRequirements().get(0).getDescription());
        assertTrue(d.handle(AgentV2ToolDiscovery.BIND,"{\"requirements\":[]}").toString().contains("COUNT_MISMATCH"));
    }
    @Test void bindingCannotRewriteFrozenDatesProjectsOrMetrics() {
        AgentV2Models.TaskSpec task=spec();AgentV2Models.TaskRequirement r=task.getRequirements().get(0);
        r.setStartDate("2025-01-01");r.setEndDate("2025-12-31");r.setProjectIds(List.of("p1"));
        r.setMetricId("approved.metric");r.setRequireComplete(true);
        AgentV2ToolDiscovery d=discovery(task);d.handle(AgentV2ToolDiscovery.DESCRIBE,"{\"names\":[\"market_search\"]}");
        Object result=d.handle(AgentV2ToolDiscovery.BIND,"{\"requirements\":[{\"index\":0,\"capabilityCodes\":[\"market.search\"],\"criteria\":{\"city\":\"杭州\"},\"startDate\":\"2026-01-01\",\"projectIds\":[\"p2\"],\"metricId\":\"wrong\"}]}");
        assertFalse(result.toString().contains("error"));
        AgentV2Models.TaskRequirement bound=task.getRequirements().get(0);
        assertEquals("2025-01-01",bound.getStartDate());assertEquals("2025-12-31",bound.getEndDate());
        assertEquals(List.of("p1"),bound.getProjectIds());assertEquals("approved.metric",bound.getMetricId());assertTrue(bound.getRequireComplete());
    }
    @Test void nativeNamesAndCanonicalCodesNormalizeOnlyWithinAuthorizedDiscoveredTools() {
        AgentV2Models.TaskSpec spec=spec();AgentV2ToolDiscovery d=discovery(spec);
        d.handle(AgentV2ToolDiscovery.DESCRIBE,"{\"names\":[\"market.search\",\"admin.secret\"]}");
        assertTrue(d.isLoaded("market_search"));assertFalse(d.isLoaded("admin.secret"));
        Object result=d.handle(AgentV2ToolDiscovery.BIND,"{\"requirements\":[{\"index\":0,\"capabilityCodes\":[\"market_search\"],\"criteria\":{\"city\":\"杭州\"}}]}");
        assertFalse(result.toString().contains("error"));assertTrue(d.isBound());
        assertEquals(List.of("market.search"),spec.getRequirements().get(0).getCapabilityCodes());
    }
    @Test void interpretationSummaryDoesNotGrowWithLongSchemasOrExposeUnavailableCapabilities() throws Exception {
        List<Object> entries=new ArrayList<>();
        for(int i=0;i<77;i++)entries.add(Map.of("code","project.tool"+i,"name","查询"+i,"available",true,"description","X".repeat(5000)));
        entries.add(Map.of("code","admin.secret","name","SECRET","available",false));
        Map<String,Object> manifest=Map.of("capabilities",entries);
        String summary=json.writeValueAsString(AgentV2ToolDiscovery.summary(manifest,json,false));
        assertTrue(summary.length()<600);assertFalse(summary.contains("SECRET"));assertFalse(summary.contains("XXXX"));
    }
    @Test void malformedOutputIsRetriedOnceAndBothCallsAreAccounted() {
        QueueGateway g=new QueueGateway(response("{bad",null),response(valid(),null));
        AtomicInteger before=new AtomicInteger(),after=new AtomicInteger();
        AgentV2Models.InterpretationResult r=new AgentV2ConversationInterpreter(g,json).interpret(context(),new AgentV2Models.ScopeSnapshot(),Map.of(),"Asia/Shanghai",before::incrementAndGet,m->after.incrementAndGet());
        assertTrue(r.isSuccess());assertEquals(2,before.get());assertEquals(2,after.get());assertEquals(22,r.getCompletionTokens());
        assertEquals("INVALID_JSON",r.getDiagnostics().get(0).get("errorCode"));
        assertTrue(r.getInterpretation().getTaskSpec().getRequirements().get(0).getCapabilityCodes().isEmpty());
        assertFalse(g.messages.get(1).toString().contains("{bad"));
    }
    @Test void outputTruncationNeverBecomesAValidInterpretationEvenWhenJsonIsParseable() {
        QueueGateway g=new QueueGateway(response(valid(),"length"),response(valid(),"length"));
        AgentV2Models.InterpretationResult r=new AgentV2ConversationInterpreter(g,json).interpret(context(),new AgentV2Models.ScopeSnapshot(),Map.of(),"Asia/Shanghai");
        assertFalse(r.isSuccess());assertEquals("OUTPUT_TRUNCATED",r.getErrorCode());assertEquals(2,r.getDiagnostics().size());assertNull(r.getInterpretation());
    }
    @Test void wrongFieldTypesAreDiagnosedAndDoNotReclassifyTheBusinessGoal() {
        QueueGateway g=new QueueGateway(response(valid().replace("\"requestedMetrics\":[]","\"requestedMetrics\":{}"),null),response(valid(),null));
        AgentV2Models.InterpretationResult r=new AgentV2ConversationInterpreter(g,json).interpret(context(),new AgentV2Models.ScopeSnapshot(),Map.of(),"Asia/Shanghai");
        assertTrue(r.isSuccess());assertEquals("SCHEMA_TYPE_ERROR",r.getDiagnostics().get(0).get("errorCode"));
    }
    @Test void modelUnavailabilityDoesNotTriggerFormatRepairOrBusinessExecution() {
        AgentModelGateway.ModelResult failed=response("",null);failed.setSuccess(false);failed.setErrorCode("MODEL_CALL_FAILED");
        QueueGateway g=new QueueGateway(failed);
        AgentV2Models.InterpretationResult r=new AgentV2ConversationInterpreter(g,json).interpret(context(),new AgentV2Models.ScopeSnapshot(),Map.of(),"Asia/Shanghai");
        assertFalse(r.isSuccess());assertEquals(1,g.messages.size());assertEquals("MODEL_CALL_FAILED",r.getErrorCode());
    }
    private AgentV2Models.BoundedContext context(){AgentV2Models.BoundedContext c=new AgentV2Models.BoundedContext();AgentV2Models.ContextMessage m=new AgentV2Models.ContextMessage();m.setRole("user");m.setContent("从企业经营的角度，你会给我哪些建议");c.setMessages(List.of(m));return c;}
    private static String valid(){return "{\"dialogueAct\":\"BUSINESS_QUERY\",\"relationType\":\"NEW\",\"taskSpec\":{\"goal\":\"企业经营建议\",\"resolvedGoal\":\"企业经营建议\",\"analysisTarget\":\"ENTERPRISE\",\"requestedMetrics\":[],\"requirements\":[{\"description\":\"企业经营建议\",\"capabilityCodes\":[\"hallucinated.tool\"],\"criteria\":{}}]}}";}
    private static AgentModelGateway.ModelResult response(String text,String finish){AgentModelGateway.ModelResult r=new AgentModelGateway.ModelResult();r.setSuccess(true);r.setContent(text);r.setProvider("test");r.setModel("test");r.setFinishReason(finish);r.setCompletionTokens(11);return r;}
    private static class QueueGateway implements AgentV2ModelGateway {
        final Deque<AgentModelGateway.ModelResult> results;final List<List<Map<String,Object>>> messages=new ArrayList<>();
        QueueGateway(AgentModelGateway.ModelResult... results){this.results=new ArrayDeque<>(Arrays.asList(results));}
        public boolean isAvailable(){return true;}
        public AgentModelGateway.ModelResult complete(List<Map<String,Object>> m,List<Map<String,Object>> tools,String reasoning){messages.add(m);return results.removeFirst();}
    }
}
