package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentV2GuidanceContractRecoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void invalidFieldsReceiveSpecificRepairWithoutLeakingProviderContent() throws Exception {
        Map<String,String> invalid = new LinkedHashMap<>();
        invalid.put("dialogueAct", "GUIDANCE");
        invalid.put("relationType", "FOLLOW_UP");
        invalid.put("analysisTarget", "SOFTWARE");
        invalid.put("startDate", "SECRET_INVALID_DATE");
        for (Map.Entry<String,String> entry : invalid.entrySet()) {
            Map<String,Object> output=valid();
            Map<String,Object> spec=(Map<String,Object>)output.get("taskSpec");
            if(entry.getKey().equals("startDate")) ((Map<String,Object>)((List<?>)spec.get("requirements")).get(0)).put(entry.getKey(),entry.getValue());
            else if(entry.getKey().equals("analysisTarget")) spec.put(entry.getKey(),entry.getValue());
            else output.put(entry.getKey(),entry.getValue());
            Gateway gateway=new Gateway(mapper.writeValueAsString(output), mapper.writeValueAsString(valid()));
            AgentV2Models.InterpretationResult result=run(gateway,"我是新用户，应该从哪里开始");
            assertTrue(result.isSuccess(),entry.getKey());
            assertTrue(result.getDiagnostics().get(0).get("validationField").toString().endsWith(entry.getKey()));
            String retry=gateway.requests.get(1).get(2).get("content").toString();
            assertTrue(retry.contains(entry.getKey()));
            assertFalse(retry.contains("SECRET_INVALID_DATE"));
            assertFalse(result.getDiagnostics().toString().contains("SECRET_INVALID_DATE"));
        }
    }

    @Test void retryCannotBypassFrozenProjectAuthorization() throws Exception {
        Map<String,Object> bad=valid();
        Map<String,Object> spec=(Map<String,Object>)bad.get("taskSpec");
        ((Map<String,Object>)((List<?>)spec.get("requirements")).get(0)).put("projectIds",List.of("unauthorized"));
        Gateway gateway=new Gateway(mapper.writeValueAsString(bad),mapper.writeValueAsString(bad));
        AgentV2Models.InterpretationResult result=run(gateway,"查项目未归还材料");
        assertFalse(result.isSuccess()); assertNull(result.getInterpretation());
        assertEquals("taskSpec.requirements.projectIds",result.getDiagnostics().get(1).get("validationField"));
        assertFalse(result.getWarning().contains("理解"));
        assertEquals(2,gateway.requests.size());
    }

    @Test void twoGuidanceFollowUpsRetainHistoryAndNeedNoProjectOrDate() throws Exception {
        for(String question:List.of("我是新用户，应该从哪里开始","怎么新建租出项目")) {
            Gateway gateway=new Gateway(mapper.writeValueAsString(valid()));
            AgentV2Models.InterpretationResult result=run(gateway,question);
            assertTrue(result.isSuccess());
            assertEquals(List.of("GUIDANCE"),result.getInterpretation().getTaskSpec().getTaskKinds());
            assertTrue(result.getInterpretation().getTaskSpec().getMissingInputs().isEmpty());
            String data=gateway.requests.get(0).get(1).get("content").toString();
            assertTrue(data.contains(question)); assertTrue(data.contains("你现在支持哪些操作"));
            String system=gateway.requests.get(0).get(0).get("content").toString();
            assertFalse(system.contains("BUSINESS_QUERY|"));
        }
    }

    @Test void schemaRepairNamesFieldWithoutExposingRejectedValue() throws Exception {
        Map<String,Object> bad=valid();
        ((Map<String,Object>)bad.get("taskSpec")).put("scopeIntent",Map.of("secret","PRIVATE_VALUE"));
        Gateway gateway=new Gateway(mapper.writeValueAsString(bad),mapper.writeValueAsString(valid()));
        AgentV2Models.InterpretationResult result=run(gateway,"查询A项目");
        assertTrue(result.isSuccess());
        assertEquals("taskSpec.scopeIntent",result.getDiagnostics().get(0).get("validationField"));
        assertFalse(gateway.requests.get(1).get(2).toString().contains("PRIVATE_VALUE"));
        String envelope=gateway.requests.get(0).get(1).get("content").toString();
        assertEquals("查询A项目",mapper.readTree(envelope.substring("DATA_ENVELOPE=".length())).path("currentUserMessage").asText());
    }

    private AgentV2Models.InterpretationResult run(Gateway gateway,String question) {
        AgentV2Models.BoundedContext context=new AgentV2Models.BoundedContext();
        List<AgentV2Models.ContextMessage> messages=new ArrayList<>();
        for(String[] pair:new String[][]{{"user","你现在支持哪些操作"},{"assistant","可以询问新手入门、创建项目和材料归还操作。"},{"user",question}}) {
            AgentV2Models.ContextMessage message=new AgentV2Models.ContextMessage();message.setRole(pair[0]);message.setContent(pair[1]);messages.add(message);
        }
        context.setMessages(messages);context.setPreviousTask(Map.of("resolvedGoal","了解软件能力","status","COMPLETED"));
        return new AgentV2ConversationInterpreter(gateway,mapper).interpret(context,new AgentV2Models.ScopeSnapshot(),Map.of(),"Asia/Shanghai");
    }
    private Map<String,Object> valid() {
        Map<String,Object> requirement=new LinkedHashMap<>();requirement.put("description","介绍软件操作步骤");requirement.put("projectIds",List.of());
        Map<String,Object> spec=new LinkedHashMap<>();spec.put("resolvedGoal","介绍软件操作步骤");spec.put("taskKinds",List.of("GUIDANCE"));spec.put("analysisTarget","OTHER");spec.put("requirements",List.of(requirement));
        Map<String,Object> output=new LinkedHashMap<>();output.put("dialogueAct","BUSINESS_QUERY");output.put("relationType","NEW");output.put("taskSpec",spec);return output;
    }
    private static class Gateway implements AgentV2ModelGateway {
        private final Deque<String> outputs;private final List<List<Map<String,Object>>> requests=new ArrayList<>();
        Gateway(String... outputs){this.outputs=new ArrayDeque<>(Arrays.asList(outputs));}
        public boolean isAvailable(){return true;}
        public AgentModelGateway.ModelResult complete(List<Map<String,Object>> messages,List<Map<String,Object>> tools,String effort){
            requests.add(messages);AgentModelGateway.ModelResult result=new AgentModelGateway.ModelResult();result.setSuccess(true);result.setContent(outputs.removeFirst());return result;
        }
    }
}
