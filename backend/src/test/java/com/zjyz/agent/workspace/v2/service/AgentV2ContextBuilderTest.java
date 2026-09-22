package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentV2ContextBuilderTest {

    @Test
    void doesNotAppendCurrentUserMessageTwiceWhenCallerAlreadyPersistedIt() {
        AgentV2ContextBuilder builder = new AgentV2ContextBuilder(new ObjectMapper());
        AgentRuntimeRecords.Message current = new AgentRuntimeRecords.Message();
        current.setRole("user");
        current.setContent("然后呢");
        current.setCreatedAt(LocalDateTime.now());

        AgentV2Models.BoundedContext context = builder.build(new AgentRuntimeRecords.AgentThread(),
                Collections.singletonList(current), null, "然后呢", Collections.emptyMap());

        assertEquals(1, context.getMessages().size());
        assertEquals("然后呢", context.getMessages().get(0).getContent());
    }

    @Test
    void clarificationContextPreservesMetricAndTime() throws Exception {
        AgentV2Models.Task previous = new AgentV2Models.Task();
        previous.setTaskId("task-money");
        previous.setTaskSpecJson("{\"resolvedGoal\":\"哪个材料最赚钱\",\"analysisTarget\":\"MATERIAL\","
                + "\"requestedMetrics\":[\"MONEY_UNSPECIFIED\"],\"timeRangeExpression\":\"今年\"}");
        AgentV2Models.BoundedContext context = new AgentV2ContextBuilder(new ObjectMapper()).build(
                new AgentRuntimeRecords.AgentThread(), Collections.emptyList(), previous,
                "看租金收入", Collections.emptyMap());
        assertEquals("MATERIAL", context.getPreviousTask().get("analysisTarget"));
        assertEquals(Collections.singletonList("MONEY_UNSPECIFIED"), context.getPreviousTask().get("requestedMetrics"));
        assertEquals("今年", context.getPreviousTask().get("timeRangeExpression"));
    }

    @Test
    void hardBudgetNeverLeaksFullPreviousScopeOrExceedsConfiguredFloor() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentV2ContextBuilder builder = new AgentV2ContextBuilder(objectMapper);
        ReflectionTestUtils.setField(builder, "maxCharacters", 1);
        ReflectionTestUtils.setField(builder, "maxMessageCharacters", 1);
        AgentRuntimeRecords.AgentThread thread = new AgentRuntimeRecords.AgentThread();
        thread.setSummary(String.join("", Collections.nCopies(5000, "摘")));
        AgentV2Models.Task previous = new AgentV2Models.Task();
        previous.setTaskId("task-1");
        previous.setStatus("COMPLETED");
        previous.setGoal("上个月租出排行");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("resolvedGoal", "统计上个月全部项目的租出材料排行");
        previous.setTaskSpecJson(objectMapper.writeValueAsString(spec));
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("scopeHash", "scope-hash");
        scope.put("projectCount", 2000);
        ArrayList<String> ids = new ArrayList<>();
        for (int index = 0; index < 2000; index++) {
            ids.add("project-" + index);
        }
        scope.put("projectIds", ids);
        previous.setScopeJson(objectMapper.writeValueAsString(scope));
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("large", String.join("", Collections.nCopies(6000, "页")));

        AgentV2Models.BoundedContext context = builder.build(thread, Collections.emptyList(), previous,
                String.join("", Collections.nCopies(2000, "问")), ui);
        String json = objectMapper.writeValueAsString(context);

        assertTrue(context.getUsedCharacters() <= context.getMaxCharacters());
        assertTrue(!json.contains("project-1999"));
        assertTrue(json.contains("scope-hash") || context.getPreviousTask() == null);
    }
}
