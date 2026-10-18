package com.zjyz.agent.workspace.v2.modelgateway;

import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.modelgateway.GlmAgentModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentV2ModelRouterTest {

    @Test
    void interpretationFallbackPreservesStructuredTransport() {
        GlmAgentModelGateway glm = mock(GlmAgentModelGateway.class);
        AgentV2OpenAiCompatibleClient compatible = mock(AgentV2OpenAiCompatibleClient.class);
        when(glm.isAvailable()).thenReturn(true);
        when(glm.completeInterpretation(anyList())).thenReturn(failure("zhipu"));
        when(compatible.isAvailable("deepseek")).thenReturn(true);
        when(compatible.interpret(eq("deepseek"), anyList())).thenReturn(success("deepseek", "test"));
        assertTrue(fallbackRouter(glm, compatible).interpret(Collections.emptyList()).isSuccess());
        verify(glm).completeInterpretation(anyList());
        verify(compatible).interpret(eq("deepseek"), anyList());
        verify(compatible, never()).complete(anyString(), anyList(), anyList());
    }

    @Test
    void glmFailureFallsBackToDeepSeekAndKeepsStructuredResult() {
        GlmAgentModelGateway glm = mock(GlmAgentModelGateway.class);
        AgentV2OpenAiCompatibleClient compatible = mock(AgentV2OpenAiCompatibleClient.class);
        when(glm.isAvailable()).thenReturn(true);
        when(glm.complete(anyList(), anyList(), anyString())).thenReturn(failure("zhipu"));
        when(compatible.isAvailable(AgentV2OpenAiCompatibleClient.PROVIDER_DEEPSEEK)).thenReturn(true);
        AgentModelGateway.ModelResult deepSeek = success("deepseek", "deepseek-v4-flash");
        deepSeek.setPromptTokens(17);deepSeek.setCompletionTokens(9);
        AgentModelGateway.ToolCall toolCall = new AgentModelGateway.ToolCall();
        toolCall.setId("call-1");
        toolCall.setName("query_business_fact");
        toolCall.setArguments("{\"scope\":\"frozen\"}");
        deepSeek.setToolCalls(Collections.singletonList(toolCall));
        when(compatible.complete(eq(AgentV2OpenAiCompatibleClient.PROVIDER_DEEPSEEK),
                anyList(), anyList())).thenReturn(deepSeek);

        AgentV2ModelRouter router = fallbackRouter(glm, compatible);
        AgentModelGateway.ModelResult result = router.complete(
                Collections.emptyList(), Collections.emptyList(), "medium");

        assertTrue(result.isSuccess());
        assertEquals("deepseek", result.getProvider());
        assertEquals(2,result.getAttempts().size());
        assertEquals("UNKNOWN",result.getAttempts().get(0).getUsageStatus());
        assertEquals("KNOWN",result.getAttempts().get(1).getUsageStatus());
        assertEquals(17,result.getAttempts().get(1).getPromptTokens());
        assertEquals("query_business_fact", result.getToolCalls().get(0).getName());
        verify(compatible, never()).complete(eq(AgentV2OpenAiCompatibleClient.PROVIDER_OPENAI),
                anyList(), anyList());
    }

    @Test
    void failedProviderIsSkippedDuringCooldown() {
        GlmAgentModelGateway glm = mock(GlmAgentModelGateway.class);
        AgentV2OpenAiCompatibleClient compatible = mock(AgentV2OpenAiCompatibleClient.class);
        when(glm.isAvailable()).thenReturn(true);
        when(glm.complete(anyList(), anyList(), anyString())).thenReturn(failure("zhipu"));
        when(compatible.isAvailable(AgentV2OpenAiCompatibleClient.PROVIDER_DEEPSEEK)).thenReturn(true);
        when(compatible.complete(eq(AgentV2OpenAiCompatibleClient.PROVIDER_DEEPSEEK),
                anyList(), anyList())).thenReturn(success("deepseek", "deepseek-v4-flash"));
        AgentV2ModelRouter router = new AgentV2ModelRouter(glm, compatible);
        ReflectionTestUtils.setField(router, "fallbackEnabled", true);
        ReflectionTestUtils.setField(router, "providerOrder", "glm,deepseek,openai");
        ReflectionTestUtils.setField(router, "failureCooldownMs", 60_000L);

        assertTrue(router.complete(Collections.emptyList(), Collections.emptyList(), "low").isSuccess());
        assertTrue(router.complete(Collections.emptyList(), Collections.emptyList(), "low").isSuccess());

        verify(glm, times(1)).complete(anyList(), anyList(), anyString());
        verify(compatible, times(2)).complete(eq(AgentV2OpenAiCompatibleClient.PROVIDER_DEEPSEEK),
                anyList(), anyList());
    }

    @Test
    void allConfiguredProvidersFailClosed() {
        GlmAgentModelGateway glm = mock(GlmAgentModelGateway.class);
        AgentV2OpenAiCompatibleClient compatible = mock(AgentV2OpenAiCompatibleClient.class);
        when(glm.isAvailable()).thenReturn(true);
        when(glm.complete(anyList(), anyList(), anyString())).thenReturn(failure("zhipu"));
        when(compatible.isAvailable(AgentV2OpenAiCompatibleClient.PROVIDER_DEEPSEEK)).thenReturn(true);
        when(compatible.isAvailable(AgentV2OpenAiCompatibleClient.PROVIDER_OPENAI)).thenReturn(true);
        when(compatible.complete(anyString(), anyList(), anyList())).thenAnswer(invocation ->
                failure(invocation.getArgument(0)));

        AgentV2ModelRouter router = fallbackRouter(glm, compatible);
        AgentModelGateway.ModelResult result = router.complete(
                Collections.emptyList(), Collections.emptyList(), "medium");

        assertFalse(result.isSuccess());
        assertEquals("v2-router", result.getProvider());
        assertTrue(result.getWarning().contains("安全重试"));
        verify(compatible).complete(eq(AgentV2OpenAiCompatibleClient.PROVIDER_DEEPSEEK),
                anyList(), anyList());
        verify(compatible).complete(eq(AgentV2OpenAiCompatibleClient.PROVIDER_OPENAI),
                anyList(), anyList());
    }

    @Test
    void disablingFallbackLeavesLegacyGlmAsTheOnlyV2Provider() {
        GlmAgentModelGateway glm = mock(GlmAgentModelGateway.class);
        AgentV2OpenAiCompatibleClient compatible = mock(AgentV2OpenAiCompatibleClient.class);
        when(glm.isAvailable()).thenReturn(true);
        when(glm.complete(anyList(), anyList(), anyString())).thenReturn(failure("zhipu"));
        AgentV2ModelRouter router = new AgentV2ModelRouter(glm, compatible);
        ReflectionTestUtils.setField(router, "fallbackEnabled", false);

        AgentModelGateway.ModelResult result = router.complete(
                Collections.emptyList(), Collections.emptyList(), "low");

        assertFalse(result.isSuccess());
        verify(compatible, never()).complete(anyString(), anyList(), anyList());
    }

    @Test
    void invalidExplicitProviderOrderFailsClosedInsteadOfExpandingToAllProviders() {
        GlmAgentModelGateway glm = mock(GlmAgentModelGateway.class);
        AgentV2OpenAiCompatibleClient compatible = mock(AgentV2OpenAiCompatibleClient.class);
        AgentV2ModelRouter router = new AgentV2ModelRouter(glm, compatible);
        ReflectionTestUtils.setField(router, "fallbackEnabled", true);
        ReflectionTestUtils.setField(router, "providerOrder", "deepseak");

        assertFalse(router.isAvailable());
        AgentModelGateway.ModelResult result = router.complete(
                Collections.emptyList(), Collections.emptyList(), "low");

        assertFalse(result.isSuccess());
        assertTrue(result.getWarning().contains("联系管理员"));
        verify(glm, never()).complete(anyList(), anyList(), anyString());
        verify(compatible, never()).complete(anyString(), anyList(), anyList());
    }

    @Test
    void singleConfiguredProviderInCooldownPerformsHalfOpenProbeAndRecovers() {
        GlmAgentModelGateway glm = mock(GlmAgentModelGateway.class);
        AgentV2OpenAiCompatibleClient compatible = mock(AgentV2OpenAiCompatibleClient.class);
        when(glm.isAvailable()).thenReturn(true);
        when(glm.complete(anyList(), anyList(), anyString()))
                .thenReturn(failure("glm"), success("glm", "test"));
        AgentV2ModelRouter router = new AgentV2ModelRouter(glm, compatible);
        assertFalse(router.complete(Collections.emptyList(), Collections.emptyList(), "low").isSuccess());
        AgentModelGateway.ModelResult recovered = router.complete(Collections.emptyList(), Collections.emptyList(), "low");
        assertTrue(recovered.isSuccess());
        assertEquals(1, recovered.getAttempts().size());
        assertTrue(((java.util.Map<?, ?>) ReflectionTestUtils.getField(router, "coolingUntil")).isEmpty());
        assertTrue(router.complete(Collections.emptyList(), Collections.emptyList(), "low").isSuccess());
        verify(glm, times(3)).complete(anyList(), anyList(), anyString());
        verify(compatible, never()).complete(anyString(), anyList(), anyList());
    }

    @Test
    void allCoolingProvidersProbeOnlyTheFirstConfiguredProvider() {
        GlmAgentModelGateway glm = mock(GlmAgentModelGateway.class);
        AgentV2OpenAiCompatibleClient compatible = mock(AgentV2OpenAiCompatibleClient.class);
        when(glm.isAvailable()).thenReturn(true);
        when(compatible.isAvailable("deepseek")).thenReturn(true);
        when(glm.complete(anyList(), anyList(), anyString())).thenReturn(failure("glm"));
        when(compatible.complete(anyString(), anyList(), anyList())).thenReturn(failure("deepseek"));
        AgentV2ModelRouter router = fallbackRouter(glm, compatible);
        assertFalse(router.complete(Collections.emptyList(), Collections.emptyList(), "low").isSuccess());
        AgentModelGateway.ModelResult probe = router.complete(Collections.emptyList(), Collections.emptyList(), "low");
        assertFalse(probe.isSuccess());
        assertEquals(1, probe.getAttempts().size());
        assertEquals("glm", probe.getAttempts().get(0).getProvider());
        verify(glm, times(2)).complete(anyList(), anyList(), anyString());
        verify(compatible, times(1)).complete(eq("deepseek"), anyList(), anyList());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OUTPUT_TRUNCATED", "EMPTY_CONTENT"})
    void halfOpenInterpretationTerminalResultClearsCooldown(String errorCode) {
        GlmAgentModelGateway glm = mock(GlmAgentModelGateway.class);
        AgentV2OpenAiCompatibleClient compatible = mock(AgentV2OpenAiCompatibleClient.class);
        when(compatible.isAvailable("deepseek")).thenReturn(true);
        AgentModelGateway.ModelResult terminal = failure("deepseek");
        terminal.setErrorCode(errorCode);
        when(compatible.interpret(eq("deepseek"), anyList())).thenReturn(failure("deepseek"), terminal);
        AgentV2ModelRouter router = fallbackRouter(glm, compatible);
        assertFalse(router.interpret(Collections.emptyList()).isSuccess());
        assertEquals(errorCode, router.interpret(Collections.emptyList()).getErrorCode());
        assertTrue(((java.util.Map<?, ?>) ReflectionTestUtils.getField(router, "coolingUntil")).isEmpty());
        verify(compatible, times(2)).interpret(eq("deepseek"), anyList());
        verify(compatible, never()).complete(anyString(), anyList(), anyList());
    }

    private AgentV2ModelRouter fallbackRouter(GlmAgentModelGateway glm,
                                              AgentV2OpenAiCompatibleClient compatible) {
        AgentV2ModelRouter router = new AgentV2ModelRouter(glm, compatible);
        ReflectionTestUtils.setField(router, "fallbackEnabled", true);
        ReflectionTestUtils.setField(router, "providerOrder", "glm,deepseek,openai");
        return router;
    }

    private AgentModelGateway.ModelResult success(String provider, String model) {
        AgentModelGateway.ModelResult result = new AgentModelGateway.ModelResult();
        result.setSuccess(true);
        result.setProvider(provider);
        result.setModel(model);
        result.setContent("ok");
        return result;
    }

    private AgentModelGateway.ModelResult failure(String provider) {
        AgentModelGateway.ModelResult result = new AgentModelGateway.ModelResult();
        result.setProvider(provider);
        result.setWarning("unavailable");
        return result;
    }
}
