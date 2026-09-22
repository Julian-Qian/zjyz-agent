package com.zjyz.agent.workspace.modelgateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
class GlmInterpretationResponseTest {
    private GlmAgentModelGateway gateway(){GlmAgentModelGateway g=new GlmAgentModelGateway();ReflectionTestUtils.setField(g,"objectMapper",new ObjectMapper());return g;}
    @Test void truncatedThinkingOnlyResponseKeepsUsageAndReportsIncomplete() throws Exception {
        AgentModelGateway.ModelResult r=gateway().decodeResponse("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"private reasoning\"}}],\"usage\":{\"prompt_tokens\":8956,\"completion_tokens\":4096}}",true);
        assertFalse(r.isSuccess());assertEquals("OUTPUT_TRUNCATED",r.getErrorCode());assertEquals(4096,r.getCompletionTokens());assertEquals(8956,r.getPromptTokens());assertEquals("length",r.getFinishReason());
    }
    @Test void normalJsonAndEmptyContentHaveDifferentOutcomes() throws Exception {
        String raw="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}]}";
        assertTrue(gateway().decodeResponse(raw,true).isSuccess());
        AgentModelGateway.ModelResult empty=gateway().decodeResponse("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}",true);
        assertFalse(empty.isSuccess());assertEquals("EMPTY_CONTENT",empty.getErrorCode());
    }
    @Test void interpretationUsesIndependentJsonBudgetWithoutChangingPlanner() {
        GlmAgentModelGateway g=gateway();
        ReflectionTestUtils.setField(g,"model","glm-4.5-air");
        ReflectionTestUtils.setField(g,"maxOutputTokens",4096);
        ReflectionTestUtils.setField(g,"interpretationMaxTokens",2048);
        java.util.Map<String,Object> interpretation=g.requestBody(java.util.List.of(),java.util.List.of(),"low",true);
        java.util.Map<String,Object> planner=g.requestBody(java.util.List.of(),java.util.List.of(),"high",false);
        assertEquals(java.util.Map.of("type","json_object"),interpretation.get("response_format"));
        assertEquals(java.util.Map.of("type","disabled"),interpretation.get("thinking"));
        assertEquals(2048,interpretation.get("max_tokens"));
        assertFalse(planner.containsKey("response_format"));
        assertEquals(java.util.Map.of("type","enabled"),planner.get("thinking"));
        assertEquals(4096,planner.get("max_tokens"));
    }
}
