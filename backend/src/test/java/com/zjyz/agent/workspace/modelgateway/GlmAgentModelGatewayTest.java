package com.zjyz.agent.workspace.modelgateway;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;

class GlmAgentModelGatewayTest {

    private final GlmAgentModelGateway gateway = new GlmAgentModelGateway();

    @Test
    void reasoningEffortIsOmittedForGlm45Air() {
        assertFalse(gateway.supportsReasoningEffort("glm-4.5-air"));
    }

    @Test
    void reasoningEffortRemainsAvailableForGlm52Family() {
        assertTrue(gateway.supportsReasoningEffort("glm-5.2"));
        assertTrue(gateway.supportsReasoningEffort("glm-5.2-plus"));
    }

    @Test
    void modelPriorityUsesGlm52BeforeConfiguredFallbacks() {
        ReflectionTestUtils.setField(gateway, "model", "glm-5.2");
        ReflectionTestUtils.setField(gateway, "fallbackModels", "glm-4.5-air, glm-5.2");

        assertEquals(Arrays.asList("glm-5.2", "glm-4.5-air"), gateway.modelsInPriorityOrder());
    }

    @Test
    void primaryFailureFallsBackToNextGlmModel() {
        GlmAgentModelGateway candidateGateway = spy(new GlmAgentModelGateway());
        ReflectionTestUtils.setField(candidateGateway, "enabled", true);
        ReflectionTestUtils.setField(candidateGateway, "apiKey", "test-key");
        ReflectionTestUtils.setField(candidateGateway, "model", "glm-5.2");
        ReflectionTestUtils.setField(candidateGateway, "fallbackModels", "glm-4.5-air");
        doReturn(failure("glm-5.2")).when(candidateGateway).completeModel(
                eq("glm-5.2"), anyList(), anyList(), anyString());
        doReturn(success("glm-4.5-air")).when(candidateGateway).completeModel(
                eq("glm-4.5-air"), anyList(), anyList(), anyString());

        AgentModelGateway.ModelResult result = candidateGateway.complete(
                Collections.emptyList(), Collections.emptyList(), "high");

        assertTrue(result.isSuccess());
        assertEquals("glm-4.5-air", result.getModel());
        InOrder order = inOrder(candidateGateway);
        order.verify(candidateGateway).completeModel(eq("glm-5.2"), anyList(), anyList(), eq("high"));
        order.verify(candidateGateway).completeModel(eq("glm-4.5-air"), anyList(), anyList(), eq("high"));
    }

    private AgentModelGateway.ModelResult success(String model) {
        AgentModelGateway.ModelResult result = new AgentModelGateway.ModelResult();
        result.setProvider("zhipu");
        result.setModel(model);
        result.setSuccess(true);
        return result;
    }

    private AgentModelGateway.ModelResult failure(String model) {
        AgentModelGateway.ModelResult result = new AgentModelGateway.ModelResult();
        result.setProvider("zhipu");
        result.setModel(model);
        result.setWarning("unavailable");
        return result;
    }
}
