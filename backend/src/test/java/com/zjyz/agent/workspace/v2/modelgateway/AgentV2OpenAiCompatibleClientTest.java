package com.zjyz.agent.workspace.v2.modelgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentV2OpenAiCompatibleClientTest {

    @Test
    void deepSeekBodyExcludesGlmFieldsAndPreservesToolConversation() {
        String response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
                + "\"reasoning_content\":\"private\",\"tool_calls\":[{\"id\":\"call-2\","
                + "\"type\":\"function\",\"function\":{\"name\":\"query_business_fact\","
                + "\"arguments\":\"{\\\"projectId\\\":\\\"p-1\\\"}\"}}]}}],"
                + "\"usage\":{\"input_tokens\":21,\"output_tokens\":8}}";
        CapturingTransport transport = new CapturingTransport(response);
        AgentV2OpenAiCompatibleClient client = new AgentV2OpenAiCompatibleClient(
                new ObjectMapper(), transport);
        ReflectionTestUtils.setField(client, "deepseekApiKey", "test-key");
        ReflectionTestUtils.setField(client, "deepseekModel", "deepseek-v4-flash");

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("user", "统计冻结范围内项目"));
        Map<String, Object> assistant = message("assistant", null);
        assistant.put("thinking", Collections.singletonMap("type", "enabled"));
        assistant.put("reasoning_effort", "high");
        assistant.put("reasoning_content", "must-not-leak");
        assistant.put("tool_calls", Collections.singletonList(toolCall("call-1", "query_business_fact",
                "{\"scope\":\"frozen\"}")));
        messages.add(assistant);
        Map<String, Object> toolResult = message("tool", "{\"count\":3}");
        toolResult.put("tool_call_id", "call-1");
        toolResult.put("name", "query_business_fact");
        messages.add(toolResult);

        AgentModelGateway.ModelResult result = client.complete(
                AgentV2OpenAiCompatibleClient.PROVIDER_DEEPSEEK, messages,
                Collections.singletonList(toolDefinition()));

        assertTrue(result.isSuccess());
        assertEquals("deepseek", result.getProvider());
        assertEquals(21, result.getPromptTokens());
        assertEquals(8, result.getCompletionTokens());
        assertEquals("query_business_fact", result.getToolCalls().get(0).getName());
        assertEquals("call-2", result.getToolCalls().get(0).getId());
        assertNotNull(result.getAssistantMessage().get("tool_calls"));

        assertNotNull(transport.body);
        assertEquals(Collections.singletonMap("type", "disabled"), transport.body.get("thinking"));
        assertFalse(transport.body.containsKey("reasoning_effort"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sentMessages = (List<Map<String, Object>>) transport.body.get("messages");
        Map<String, Object> sentAssistant = sentMessages.get(1);
        assertFalse(sentAssistant.containsKey("thinking"));
        assertFalse(sentAssistant.containsKey("reasoning_effort"));
        assertFalse(sentAssistant.containsKey("reasoning_content"));
        assertNotNull(sentAssistant.get("tool_calls"));
        assertEquals("call-1", sentMessages.get(2).get("tool_call_id"));
        assertTrue(transport.body.containsKey("tools"));
        assertEquals("auto", transport.body.get("tool_choice"));
    }

    @Test
    void openAiBodyNeverReceivesDeepSeekOrGlmReasoningFields() {
        CapturingTransport transport = new CapturingTransport(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");
        AgentV2OpenAiCompatibleClient client = new AgentV2OpenAiCompatibleClient(
                new ObjectMapper(), transport);
        ReflectionTestUtils.setField(client, "openAiApiKey", "test-key");
        Map<String, Object> source = message("user", "hello");
        source.put("thinking", Collections.singletonMap("type", "enabled"));
        source.put("reasoning_effort", "high");
        source.put("reasoning_content", "must-not-leak");

        AgentModelGateway.ModelResult result = client.complete(
                AgentV2OpenAiCompatibleClient.PROVIDER_OPENAI,
                Collections.singletonList(source), Collections.emptyList());

        assertTrue(result.isSuccess());
        assertFalse(transport.body.containsKey("thinking"));
        assertFalse(transport.body.containsKey("reasoning_effort"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sentMessages = (List<Map<String, Object>>) transport.body.get("messages");
        assertFalse(sentMessages.get(0).containsKey("thinking"));
        assertFalse(sentMessages.get(0).containsKey("reasoning_effort"));
        assertFalse(sentMessages.get(0).containsKey("reasoning_content"));
    }

    @Test
    void emptyCompatibleResponseFailsClosed() {
        CapturingTransport transport = new CapturingTransport(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}");
        AgentV2OpenAiCompatibleClient client = new AgentV2OpenAiCompatibleClient(
                new ObjectMapper(), transport);
        ReflectionTestUtils.setField(client, "deepseekApiKey", "test-key");

        AgentModelGateway.ModelResult result = client.complete(
                AgentV2OpenAiCompatibleClient.PROVIDER_DEEPSEEK,
                Collections.singletonList(message("user", "hello")), Collections.emptyList());

        assertFalse(result.isSuccess());
        assertTrue(result.getWarning().contains("暂时没有返回结果"));
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private Map<String, Object> toolCall(String id, String name, String arguments) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("arguments", arguments);
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", id);
        call.put("type", "function");
        call.put("function", function);
        return call;
    }

    private Map<String, Object> toolDefinition() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "query_business_fact");
        function.put("description", "read-only query");
        function.put("parameters", parameters);
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    private static class CapturingTransport implements AgentV2OpenAiCompatibleClient.HttpTransport {
        private final String response;
        private Map<String, Object> body;

        private CapturingTransport(String response) {
            this.response = response;
        }

        @Override
        public AgentV2OpenAiCompatibleClient.HttpResponse post(String url,
                                                               Map<String, Object> body,
                                                               String apiKey,
                                                               int timeoutMs) {
            this.body = body;
            return new AgentV2OpenAiCompatibleClient.HttpResponse(200, response);
        }
    }
}
