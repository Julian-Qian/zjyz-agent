package com.zjyz.agent.workspace.v2.modelgateway;

import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.modelgateway.GlmAgentModelGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** V2-only provider router: GLM primary, then DeepSeek and OpenAI compatible fallbacks. */
@Component
@Slf4j
public class AgentV2ModelRouter implements AgentV2ModelGateway {
    private static final String PROVIDER_GLM = "glm";
    private static final Set<String> SUPPORTED = new LinkedHashSet<>(Arrays.asList(
            PROVIDER_GLM, AgentV2OpenAiCompatibleClient.PROVIDER_DEEPSEEK,
            AgentV2OpenAiCompatibleClient.PROVIDER_OPENAI));

    @Value("${agent.v2.model.fallback.enabled:false}")
    private boolean fallbackEnabled = false;
    @Value("${agent.v2.model.providerOrder:glm}")
    private String providerOrder = "glm";
    @Value("${agent.v2.model.failureCooldownMs:60000}")
    private long failureCooldownMs = 60000L;

    private final GlmAgentModelGateway glmGateway;
    private final AgentV2OpenAiCompatibleClient compatibleClient;
    private final Map<String, Long> coolingUntil = new ConcurrentHashMap<>();

    public AgentV2ModelRouter(GlmAgentModelGateway glmGateway,
                              AgentV2OpenAiCompatibleClient compatibleClient) {
        this.glmGateway = glmGateway;
        this.compatibleClient = compatibleClient;
    }

    @Override
    public boolean isAvailable() {
        for (String provider : effectiveProviders()) {
            if (configured(provider)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public AgentModelGateway.ModelResult complete(List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools,
                                                   String reasoningEffort) {
        return completeInternal(messages, tools, reasoningEffort, false);
    }

    @Override
    public AgentModelGateway.ModelResult interpret(List<Map<String, Object>> messages) {
        return completeInternal(messages, Collections.emptyList(), "low", true);
    }

    private AgentModelGateway.ModelResult completeInternal(List<Map<String, Object>> messages,
            List<Map<String, Object>> tools, String reasoningEffort, boolean interpretation) {
        List<AgentModelGateway.ModelAttempt> attempts = new ArrayList<>();
        boolean configuredProviderFound = false;
        boolean providerAttempted = false;
        for (String provider : effectiveProviders()) {
            if (!configured(provider)) {
                continue;
            }
            configuredProviderFound = true;
            if (isCooling(provider)) {
                continue;
            }
            providerAttempted = true;
            AgentModelGateway.ModelResult result = interpretation && PROVIDER_GLM.equals(provider)
                    ? glmGateway.completeInterpretation(messages)
                    : call(provider, messages, tools, reasoningEffort);
            AgentModelGateway.ModelAttempt attempt=new AgentModelGateway.ModelAttempt();
            attempt.setAttemptId(java.util.UUID.randomUUID().toString());attempt.setProvider(provider);
            if(result!=null) {attempt.setModel(result.getModel());attempt.setPromptTokens(result.getPromptTokens());
                attempt.setCompletionTokens(result.getCompletionTokens());attempt.setErrorCode(result.getErrorCode());}
            attempt.setUsageStatus(result!=null && (result.getPromptTokens()>0 || result.getCompletionTokens()>0)?"KNOWN":"UNKNOWN");
            attempts.add(attempt);
            if (result != null && (result.isSuccess() || "OUTPUT_TRUNCATED".equals(result.getErrorCode())
                    || "EMPTY_CONTENT".equals(result.getErrorCode()))) {
                coolingUntil.remove(provider);
                result.setAttempts(attempts);
                return result;
            }
            markFailure(provider);
            log.warn("V2 model provider failed; provider={}, cooldownMs={}",
                    provider, Math.max(failureCooldownMs, 0L));
        }

        AgentModelGateway.ModelResult unavailable = new AgentModelGateway.ModelResult();
        unavailable.setProvider("v2-router");
        unavailable.setAttempts(attempts);
        unavailable.setErrorCode("MODEL_UNAVAILABLE");
        if (!configuredProviderFound) {
            unavailable.setWarning("智能服务暂时不可用，请联系管理员");
        } else if (!providerAttempted) {
            unavailable.setWarning("智能服务正在恢复中，请稍后重试");
        } else {
            unavailable.setWarning("智能服务暂时不可用，已完成安全重试");
        }
        return unavailable;
    }

    private AgentModelGateway.ModelResult call(String provider,
                                                List<Map<String, Object>> messages,
                                                List<Map<String, Object>> tools,
                                                String reasoningEffort) {
        if (PROVIDER_GLM.equals(provider)) {
            return glmGateway.complete(messages, tools, reasoningEffort);
        }
        return compatibleClient.complete(provider, messages, tools);
    }

    private boolean configured(String provider) {
        if (PROVIDER_GLM.equals(provider)) {
            return glmGateway.isAvailable();
        }
        return compatibleClient.isAvailable(provider);
    }

    private boolean isCooling(String provider) {
        Long until = coolingUntil.get(provider);
        if (until == null) {
            return false;
        }
        if (until <= System.currentTimeMillis()) {
            coolingUntil.remove(provider, until);
            return false;
        }
        return true;
    }

    private void markFailure(String provider) {
        long cooldown = Math.max(failureCooldownMs, 0L);
        if (cooldown <= 0L) {
            coolingUntil.remove(provider);
            return;
        }
        long now = System.currentTimeMillis();
        long until = cooldown > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + cooldown;
        coolingUntil.put(provider, until);
    }

    private List<String> effectiveProviders() {
        if (!fallbackEnabled) {
            return Collections.singletonList(PROVIDER_GLM);
        }
        if (!StringUtils.hasText(providerOrder)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        for (String value : providerOrder.split(",")) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (SUPPORTED.contains(normalized)) {
                providers.add(normalized);
            }
        }
        return new ArrayList<>(providers);
    }
}
