package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.modelgateway.*;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.tool.*;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real-model test. The transport receives JSON on stdin and returns raw provider JSON.
 * Uses synthetic scope/data; does not start Spring, connect to the business DB or execute services. */
@EnabledIfSystemProperty(named="agent.live", matches="true")
class AgentV2LiveDiscoveryRegressionTest {
    private final ObjectMapper json=new ObjectMapper();
    private final List<Map<String,Object>> report=new ArrayList<>();
    private final AgentToolCatalog catalog=new AgentToolCatalog();
    private final LiveGateway gateway=new LiveGateway();

    @Test void sixIncidentQuestionsAndNativeDiscoveryLoop() throws Exception {
        String[] questions={"这个月有哪些合同到期","目前哪些项目存在风险","帮我总结过去一年我公司的经营情况",
                "我打错字了，应该是有哪些低库存材料","截至今天，生成今年及历史结转的逾期未付款项目催缴清单","从企业经营的角度，你会给我哪些建议"};
        List<String> failures=new ArrayList<>();
        AgentV2Models.ConversationInterpretation advice=null;
        for(String question:questions) {
            AgentV2Models.BoundedContext context=context(question);
            if(question.startsWith("我打错字")) {
                context.setPreviousTask(Map.of("resolvedGoal","查看低库存材料"));
                AgentV2Models.ContextMessage prior=new AgentV2Models.ContextMessage();prior.setRole("user");prior.setContent("目前有哪些底库存材料");
                context.setMessages(List.of(prior,context.getMessages().get(0)));
            }
            AgentV2Models.InterpretationResult r=new AgentV2ConversationInterpreter(gateway,json).interpret(context,scope(),manifest(),"Asia/Shanghai");
            Map<String,Object> row=new LinkedHashMap<>();row.put("question",question);row.put("success",r.isSuccess());row.put("error",r.getErrorCode());
            row.put("diagnostics",r.getDiagnostics());row.put("interpretation",r.getInterpretation());report.add(row);save();
            if(!r.isSuccess() || !("BUSINESS_QUERY".equals(r.getInterpretation().getDialogueAct()) || "CLARIFICATION_RESPONSE".equals(r.getInterpretation().getDialogueAct()))) failures.add(question);
            if(question.equals(questions[5])) advice=r.getInterpretation();
            System.out.println("LIVE interpretation: "+question+" success="+r.isSuccess()+" tokens="+r.getCompletionTokens());
        }
        assertTrue(failures.isEmpty(),"Real-model interpretation failures: "+failures);
        assertNotNull(advice);
        // Exercise the actual bridge against the same model; business results are synthetic.
        AgentV2RunExecutor executor=new AgentV2RunExecutor(null,null,null,null,null,null,null,null,null,null,null,json);
        List<Map<String,Object>> messages=executor.plannerMessages(context(questions[5]),advice,scope(),manifest());
        AgentRuntimeRecords.Workspace workspace=new AgentRuntimeRecords.Workspace();workspace.setSelectionMode("ALL");workspace.setFinanceEnabled(true);
        AgentV2ToolDiscovery discovery=new AgentV2ToolDiscovery(catalog.modelDefinitions(workspace),catalog::canonicalToolCode,advice.getTaskSpec(),json);
        Set<String> executed=new LinkedHashSet<>();List<String> operations=new ArrayList<>();
        ReflectionTestUtils.setField(executor,"maxIterations",8);
        for(int i=0;i<executor.plannerIterationLimit(true);i++) {
            AgentModelGateway.ModelResult r=gateway.complete(messages,discovery.tools(),"medium");
            assertTrue(r.isSuccess(),"Planner provider failure");assertNotEquals("length",r.getFinishReason());
            if(r.getToolCalls().isEmpty()) break;
            messages.add(r.getAssistantMessage());
            for(AgentModelGateway.ToolCall call:r.getToolCalls()) {
                Object output;
                if(AgentV2ToolDiscovery.handles(call.getName())) {operations.add(call.getName());output=discovery.handle(call.getName(),call.getArguments());}
                else if (!discovery.isBound()) {
                    output=Map.of("error","REQUIREMENTS_UNBOUND","message","先用agent_bind_requirements绑定所有原始要求，再调用业务工具");
                } else {
                    assertTrue(discovery.isLoaded(call.getName()),"Model attempted undiscovered tool "+call.getName());
                    String code=catalog.canonicalToolCode(call.getName());executed.add(code);
                    output=Map.of("answer","当前范围查询完成，匹配记录为0；该结果仅对应本工具，不能推断其他领域或指标不存在风险。",
                            "evidence",Map.of("toolCode",code,"projectIds",List.of("synthetic-project"),"recordCount",0),"cards",List.of());
                }
                messages.add(Map.of("role","tool","tool_call_id",call.getId(),"content",json.writeValueAsString(output)));
            }
            report.add(Map.of("plannerIteration",i+1,"operations",new ArrayList<>(operations),"executed",new ArrayList<>(executed),"outputTokens",r.getCompletionTokens()));save();
            if(operations.contains(AgentV2ToolDiscovery.BIND)&&executed.size()>=2) break;
        }
        assertTrue(operations.contains(AgentV2ToolDiscovery.DESCRIBE),"No tool loading");
        assertTrue(operations.contains(AgentV2ToolDiscovery.BIND),"No requirement binding");
        assertTrue(executed.size()>=2,"Expected business analysis across at least two tools");
        assertTrue(advice.getTaskSpec().getRequirements().stream().anyMatch(r->!r.getCapabilityCodes().isEmpty()),"No successful requirement binding");
    }
    private Map<String,Object> manifest(){List<Map<String,Object>> caps=new ArrayList<>();for(AgentToolDescriptor d:catalog.descriptors())caps.add(Map.of("code",d.getToolCode(),"name",d.getModelName(),"description",d.getDescription(),"available",true));return Map.of("manifestVersion","live-synthetic","capabilities",caps);}
    private AgentV2Models.ScopeSnapshot scope(){AgentV2Models.ScopeSnapshot s=new AgentV2Models.ScopeSnapshot();s.setProjectIds(List.of("synthetic-project"));s.setProjectCount(1);s.setScopeType("TENANT");s.setEffectiveSelectionMode("ALL");s.setFrozen(true);return s;}
    private AgentV2Models.BoundedContext context(String q){AgentV2Models.BoundedContext c=new AgentV2Models.BoundedContext();AgentV2Models.ContextMessage m=new AgentV2Models.ContextMessage();m.setRole("user");m.setContent(q);c.setMessages(List.of(m));return c;}
    private void save() throws Exception {json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/agent-live-regression.json").toFile(),report);}
    private class LiveGateway implements AgentV2ModelGateway {
        private final GlmAgentModelGateway decoder=new GlmAgentModelGateway();
        LiveGateway(){ReflectionTestUtils.setField(decoder,"model","glm-4.5-air");ReflectionTestUtils.setField(decoder,"objectMapper",json);ReflectionTestUtils.setField(decoder,"maxOutputTokens",4096);}
        public boolean isAvailable(){return true;}
        public AgentModelGateway.ModelResult interpret(List<Map<String,Object>> messages){return request(messages,List.of(),"low",true);}
        public AgentModelGateway.ModelResult complete(List<Map<String,Object>> messages,List<Map<String,Object>> tools,String effort){return request(messages,tools,effort,false);}
        private AgentModelGateway.ModelResult request(List<Map<String,Object>> messages,List<Map<String,Object>> tools,String effort,boolean interpretation) {
            try {
                Map<String,Object> body=ReflectionTestUtils.invokeMethod(decoder,"requestBody",messages,tools,effort,interpretation);
                Process process=new ProcessBuilder("python3",System.getProperty("agent.live.transport")).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                process.getOutputStream().write(json.writeValueAsBytes(body));process.getOutputStream().close();
                byte[] output=process.getInputStream().readAllBytes();
                assertTrue(process.waitFor(150,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"Live transport failed");
                return ReflectionTestUtils.invokeMethod(decoder,"decodeResponse",new String(output,StandardCharsets.UTF_8),interpretation);
            }catch(Exception e){throw new IllegalStateException("Live model test transport failed",e);}
        }
    }
}
