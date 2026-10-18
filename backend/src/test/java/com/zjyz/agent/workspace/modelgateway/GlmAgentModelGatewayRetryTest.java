package com.zjyz.agent.workspace.modelgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GlmAgentModelGatewayRetryTest {
    private final RestTemplate transport = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(transport).build();
    private final GlmAgentModelGateway gateway = spy(new GlmAgentModelGateway());
    private static final String OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}";

    private void configure() {
        ReflectionTestUtils.setField(gateway, "enabled", true);
        ReflectionTestUtils.setField(gateway, "apiKey", "unit-test-key");
        ReflectionTestUtils.setField(gateway, "baseUrl", "https://model.example/v4");
        ReflectionTestUtils.setField(gateway, "model", "glm-5.2");
        ReflectionTestUtils.setField(gateway, "fallbackModels", "glm-4.7,glm-4.5-air");
        ReflectionTestUtils.setField(gateway, "maxRetries", 1);
        ReflectionTestUtils.setField(gateway, "objectMapper", new ObjectMapper());
        doReturn(transport).when(gateway).createRestTemplate();
    }

    @Test
    void retryDropsReasoningEffortAndRecoversOnSameModel() {
        configure();
        server.expect(requestTo("https://model.example/v4/chat/completions"))
                .andExpect(jsonPath("$.reasoning_effort").value("high"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"code\":\"1210\"}}"));
        server.expect(requestTo("https://model.example/v4/chat/completions"))
                .andExpect(jsonPath("$.model").value("glm-5.2"))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        assertTrue(gateway.complete(Collections.emptyList(), Collections.emptyList(), "high").isSuccess());
        server.verify();
    }

    @Test
    void quota1113SkipsRetryAndImmediatelyUsesNextModel() {
        configure();
        server.expect(requestTo("https://model.example/v4/chat/completions"))
                .andExpect(jsonPath("$.model").value("glm-5.2"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error\":{\"code\":\"1113\"}}"));
        server.expect(requestTo("https://model.example/v4/chat/completions"))
                .andExpect(jsonPath("$.model").value("glm-4.7"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        AgentModelGateway.ModelResult result = gateway.complete(Collections.emptyList(), Collections.emptyList(), "high");
        assertTrue(result.isSuccess());
        assertEquals("glm-4.7", result.getModel());
        server.verify();
    }

    @Test
    void interpretationAlsoSkipsExhaustedModelWithNumeric1113() {
        configure();
        server.expect(requestTo("https://model.example/v4/chat/completions"))
                .andExpect(jsonPath("$.model").value("glm-5.2"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error\":{\"code\":1113}}"));
        server.expect(requestTo("https://model.example/v4/chat/completions"))
                .andExpect(jsonPath("$.model").value("glm-4.7"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        assertTrue(gateway.completeInterpretation(Collections.emptyList()).isSuccess());
        server.verify();
    }

    @Test
    void otherRateLimitsStillRetryInsteadOfBeingTreatedAsResourceExhaustion() {
        configure();
        server.expect(requestTo("https://model.example/v4/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error\":{\"code\":\"1302\"}}"));
        server.expect(requestTo("https://model.example/v4/chat/completions"))
                .andExpect(jsonPath("$.model").value("glm-5.2"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        assertEquals("glm-5.2", gateway.complete(Collections.emptyList(), Collections.emptyList(), "high").getModel());
        server.verify();
    }
}
