package com.zjyz.agent.workspace.learning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.*;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.v2.service.AgentV2ConversationInterpreter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentLearningLiveEvalTest {
    private final ObjectMapper json=new ObjectMapper();
    List<Map<String,String>> cases() throws Exception {
        return json.readValue(getClass().getResourceAsStream("/agent-learning-cases.json"),new TypeReference<List<Map<String,String>>>(){});
    }
    @Test void fixtureContainsFortyDistinctCasesAndHoldout() throws Exception {
        List<Map<String,String>> cases=cases();assertEquals(40,cases.size());assertEquals(40,cases.stream().map(c->c.get("input")).distinct().count());
        assertEquals(10,cases.stream().filter(c->"holdout".equals(c.get("split"))).count());
    }
    @Test @EnabledIfEnvironmentVariable(named="XIAOYUN_RUN_LEARNING_EVAL",matches="true")
    void realModelInterpretation() throws Exception {
        String key=System.getenv("XIAOYUN_EVAL_API_KEY"),url=System.getenv("XIAOYUN_EVAL_BASE_URL"),model=System.getenv("XIAOYUN_EVAL_MODEL");
        assertNotNull(key,"Provide evaluation credentials explicitly");assertNotNull(url);assertNotNull(model);
        AgentV2OpenAiCompatibleClient client=new AgentV2OpenAiCompatibleClient(json);
        set(client,"openAiApiKey",key);set(client,"openAiBaseUrl",url);set(client,"openAiModel",model);set(client,"timeoutMs",45000);
        AgentV2ModelGateway gateway=new AgentV2ModelGateway(){
            public boolean isAvailable(){return client.isAvailable("openai");}
            public AgentModelGateway.ModelResult complete(List<Map<String,Object>> messages,List<Map<String,Object>> tools,String effort){return client.complete("openai",messages,tools);}
        };
        AgentV2ConversationInterpreter interpreter=new AgentV2ConversationInterpreter(gateway,json);
        List<Map<String,Object>> report=new ArrayList<>();int passed=0;
        for(Map<String,String> c:cases()) {
            AgentV2Models.BoundedContext context=new AgentV2Models.BoundedContext();
            AgentV2Models.ContextMessage previous=new AgentV2Models.ContextMessage();previous.setRole("assistant");previous.setContent("正在处理你的项目和材料问题。");
            AgentV2Models.ContextMessage current=new AgentV2Models.ContextMessage();current.setRole("user");current.setContent(c.get("input"));
            context.setMessages(Arrays.asList(previous,current));context.setPreviousTask(AgentLearningService.map("resolvedGoal","查询小何负责的项目，并说明租出如何影响在租数量"));
            context.setLearningContext(Arrays.asList(
                AgentLearningService.map("id","rule-1","version",1,"kind","BUSINESS_RULE","status","ACTIVE","content","租出增加在租数量"),
                AgentLearningService.map("id","preference-1","version",1,"kind","USER_PREFERENCE","status","ACTIVE","content","回答简洁")));
            AgentV2Models.ScopeSnapshot scope=new AgentV2Models.ScopeSnapshot();scope.setProjectIds(Collections.emptyList());
            Map<String,Object> manifest=AgentLearningService.map("capabilities",Arrays.asList(
                AgentLearningService.map("code","project.owner_query","available",true,"description","按负责人查询项目"),
                AgentLearningService.map("code","market.search","available",true,"description","商城找材料"),
                AgentLearningService.map("code","inventory.materials","available",true,"description","库存材料清单")));
            AgentV2Models.InterpretationResult result=interpreter.interpret(context,scope,manifest,"Asia/Shanghai");
            AgentV2Models.ConversationInterpretation i=result.getInterpretation();
            boolean pass=result.isSuccess()&&i!=null&&c.get("expectedAct").equals(i.getDialogueAct());
            if(pass&&"TEACHING".equals(i.getDialogueAct()))pass=!i.getLearningActions().isEmpty()&&i.getLearningActions().stream().allMatch(a->a.getSourceQuote()!=null&&c.get("input").contains(a.getSourceQuote()));
            if(pass&&"RULE_QUERY".equals(i.getDialogueAct()))pass=i.getSelectedMemoryIds().contains("rule-1");
            if(pass)passed++;
            report.add(AgentLearningService.map("id",c.get("id"),"split",c.get("split"),"expected",c.get("expectedAct"),"actual",i==null?null:i.getDialogueAct(),"passed",pass,"model",result.getModel()));
        }
        Files.createDirectories(Paths.get("target"));json.writerWithDefaultPrettyPrinter().writeValue(Paths.get("target/learning-live-eval.json").toFile(),report);
        assertTrue(passed>=36,"Real model interpretation must pass at least 36/40; inspect target/learning-live-eval.json");
    }
    private void set(Object target,String field,Object value)throws Exception{java.lang.reflect.Field f=target.getClass().getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
}
