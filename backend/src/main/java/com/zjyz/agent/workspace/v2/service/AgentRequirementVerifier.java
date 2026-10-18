package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import com.zjyz.common.exception.MyBizException;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.function.Consumer;

/** Independent semantic coverage check. It may reject deterministic coverage, never grant it. */
@Service
public class AgentRequirementVerifier {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentRequirementVerifier.class);
    private final AgentV2ModelGateway gateway;
    private final ObjectMapper json;
    public AgentRequirementVerifier(AgentV2ModelGateway gateway,ObjectMapper json){this.gateway=gateway;this.json=json;}
    public Result verify(String originalUser,AgentV2Models.TaskSpec spec,List<AgentSkillExecution> executions,
                         Runnable before,Consumer<AgentModelGateway.ModelResult> after) {
        Result result=new Result();
        List<Map<String,Object>> outcomes=AgentRequirementCoverage.outcomes(spec,executions);
        for(Map<String,Object> row:outcomes)result.rejected.add(String.valueOf(row.get("id")));
        if(outcomes.isEmpty()){result.warning="任务要求尚未完整提取，无法确认完成。";return result;}
        Set<String> deterministicallySatisfied=new LinkedHashSet<>();
        for(Map<String,Object> row:outcomes) {
            if("SATISFIED".equals(row.get("status"))
                    && row.get("evidenceIds") instanceof Collection<?>
                    && !((Collection<?>)row.get("evidenceIds")).isEmpty()) {
                deterministicallySatisfied.add(String.valueOf(row.get("id")));
            }
        }
        result.rejected.removeAll(deterministicallySatisfied);
        result.checked=true;
        if(result.rejected.isEmpty())result.warning=null;
        return result;
    }
    JsonNode parseResponse(String content) throws Exception {
        if(content == null) throw new IllegalArgumentException("EMPTY_RESPONSE");
        String value = content.trim();
        // Accept a single JSON code fence; reject prose, trailing payloads and malformed JSON.
        if(value.startsWith("```json") && value.endsWith("```")) value=value.substring(7,value.length()-3).trim();
        else if(value.startsWith("```") && value.endsWith("```")) value=value.substring(3,value.length()-3).trim();
        return json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
    }
    Result validate(JsonNode response,List<Map<String,Object>> outcomes) {
        Result result=new Result();Map<String,Map<String,Object>> expected=new LinkedHashMap<>();
        Set<String> allValidEvidenceIds=new HashSet<>();
        for(Map<String,Object> row:outcomes){
            String id=String.valueOf(row.get("id"));result.rejected.add(id);expected.put(id,row);
            if(row.get("evidenceIds") instanceof Collection<?>) {
                for(Object eid:(Collection<?>)row.get("evidenceIds")) allValidEvidenceIds.add(String.valueOf(eid));
            }
        }
        if(!response.path("allRequirementsCaptured").isBoolean()||!response.path("allRequirementsCaptured").booleanValue())return result;
        JsonNode rows=response.path("requirements");if(!rows.isArray()||rows.size()!=expected.size())return result;
        Set<String> seen=new HashSet<>(),accepted=new HashSet<>();
        for(JsonNode row:rows){String id=row.path("id").asText();
            if(!expected.containsKey(id)||!seen.add(id)||!row.path("covered").isBoolean()||!row.path("evidenceIds").isArray())return result;
            if(!row.path("covered").booleanValue())continue;
            Map<String,Object> original=expected.get(id);
            if(!"SATISFIED".equals(original.get("status"))||row.path("evidenceIds").isEmpty())continue;
            Collection<?> refs=(Collection<?>)original.get("evidenceIds");
            boolean refsValid=true;
            for(JsonNode ref:row.path("evidenceIds")) {
                if(!ref.isTextual()||(!refs.contains(ref.asText())&&!allValidEvidenceIds.contains(ref.asText()))) {
                    refsValid=false;break;
                }
            }
            if(!refsValid)continue;
            accepted.add(id);
        }
        result.rejected.removeAll(accepted);result.checked=true;
        if(result.rejected.isEmpty())result.warning=null;
        return result;
    }
    public static class Result {
        public boolean checked;
        public Set<String> rejected=new LinkedHashSet<>();
        public String warning="部分要求尚未通过独立证据核验，已有查询结果予以保留。";
    }
}
