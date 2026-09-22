package com.zjyz.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentLlmCallResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一的大模型调用入口。
 * 从 AgentChatServiceImpl 抽取，供 Agent 对话与项目经营报告等场景共用。
 * 提供商 key：deepseek（主力）、openai（回退）、doubao（豆包）。
 */
@Component
@Slf4j
public class AgentLlmClient {
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_DOUBAO = "doubao";

    private static final Pattern JSON_FENCE_PATTERN = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?})\\s*```", Pattern.CASE_INSENSITIVE);

    @Value("${agent.llm.enabled:true}")
    private boolean llmEnabled;

    @Value("${agent.llm.timeoutMs:12000}")
    private int llmTimeoutMs;

    @Value("${agent.llm.defaultMaxOutputTokens:600}")
    private int llmDefaultMaxOutputTokens;

    @Value("${agent.llm.deepseek.baseUrl:https://api.deepseek.com}")
    private String deepseekBaseUrl;

    @Value("${agent.llm.deepseek.apiKey:}")
    private String deepseekApiKey;

    @Value("${agent.llm.deepseek.model:deepseek-chat}")
    private String deepseekModel;

    @Value("${agent.llm.fallback.enabled:true}")
    private boolean fallbackEnabled;

    @Value("${agent.llm.fallback.baseUrl:https://api.openai.com}")
    private String fallbackBaseUrl;

    @Value("${agent.llm.fallback.apiKey:}")
    private String fallbackApiKey;

    @Value("${agent.llm.fallback.model:gpt-5-mini}")
    private String fallbackModel;

    @Value("${doubao.ark.enabled:true}")
    private boolean doubaoEnabled;

    @Value("${doubao.ark.baseUrl:https://ark.cn-beijing.volces.com/api/v3}")
    private String doubaoBaseUrl;

    @Value("${doubao.ark.apiKey:}")
    private String doubaoApiKey;

    @Value("${doubao.ark.model:}")
    private String doubaoModel;

    @Autowired
    private ObjectMapper objectMapper;

    public boolean isEnabled() {
        return llmEnabled;
    }

    public boolean isDeepseekConfigured() {
        return StringUtils.hasText(deepseekApiKey);
    }

    /**
     * 与旧版 premiumRequested 判断口径一致：不校验 fallbackModel。
     */
    public boolean isPremiumConfigured() {
        return fallbackEnabled && StringUtils.hasText(fallbackApiKey);
    }

    public boolean isFallbackAvailable() {
        return fallbackEnabled && StringUtils.hasText(fallbackApiKey) && StringUtils.hasText(fallbackModel);
    }

    public boolean isDoubaoAvailable() {
        return doubaoEnabled
                && StringUtils.hasText(doubaoBaseUrl)
                && StringUtils.hasText(doubaoApiKey)
                && StringUtils.hasText(doubaoModel);
    }

    /**
     * 调用指定提供商的 chat/completions。
     * 返回的 answer 未做长度截断，由调用方按场景自行处理。
     * 模型输出若是 {"answer":...,"confidence":...} JSON（含 ```json 围栏）则解析取值，否则按纯文本兜底。
     *
     * @param providerKey     PROVIDER_DEEPSEEK / PROVIDER_OPENAI / PROVIDER_DOUBAO
     * @param maxOutputTokens 为空时使用 agent.llm.defaultMaxOutputTokens
     */
    public AgentLlmCallResult chatCompletion(String providerKey, String systemPrompt, String userPrompt,
                                             Integer maxOutputTokens) {
        return chatCompletion(providerKey, systemPrompt, userPrompt, maxOutputTokens, null);
    }

    /**
     * @param timeoutMs 为空时使用 agent.llm.timeoutMs；长文本生成场景（如经营报告解读）可传更长超时
     */
    public AgentLlmCallResult chatCompletion(String providerKey, String systemPrompt, String userPrompt,
                                             Integer maxOutputTokens, Integer timeoutMs) {
        ProviderConfig config = resolveProvider(providerKey);
        AgentLlmCallResult result = new AgentLlmCallResult();
        result.setProvider(providerKey);
        result.setModel(config == null ? null : config.model);
        result.setSuccess(false);

        if (config == null || !StringUtils.hasText(config.baseUrl)
                || !StringUtils.hasText(config.apiKey) || !StringUtils.hasText(config.model)) {
            result.setWarning("模型配置缺失");
            return result;
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model);
            body.put("temperature", 0.1);
            body.put("max_tokens", maxOutputTokens == null ? llmDefaultMaxOutputTokens : maxOutputTokens);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(buildMessage("system", systemPrompt));
            messages.add(buildMessage("user", userPrompt));
            body.put("messages", messages);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(config.apiKey.trim());

            RestTemplate restTemplate = buildRestTemplate(timeoutMs == null ? llmTimeoutMs : timeoutMs);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    normalizeBaseUrl(config.baseUrl) + "/chat/completions",
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(response.getBody())) {
                result.setWarning("智能服务暂时不可用，请稍后重试");
                return result;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            int promptTokens = extractUsageTokens(root.path("usage"), "prompt_tokens", "input_tokens");
            int completionTokens = extractUsageTokens(root.path("usage"), "completion_tokens", "output_tokens");
            if (promptTokens <= 0) {
                promptTokens = estimatePromptTokens(systemPrompt + userPrompt);
            }
            if (completionTokens <= 0) {
                completionTokens = estimateCompletionTokens(content);
            }

            ParsedAnswer parsedAnswer = parseModelAnswer(content);
            if (!StringUtils.hasText(parsedAnswer.answer)) {
                result.setWarning("智能服务暂时没有返回有效结果，请稍后重试");
                return result;
            }

            result.setPromptTokens(promptTokens);
            result.setCompletionTokens(completionTokens);
            result.setAnswer(parsedAnswer.answer);
            result.setConfidence(parsedAnswer.confidence);
            result.setSuccess(true);
            return result;
        } catch (RestClientException e) {
            log.warn("chatCompletion rest error provider={} model={} err={}", providerKey, config.model, e.getMessage());
            result.setWarning("智能服务暂时不可用，请稍后重试");
            return result;
        } catch (Exception e) {
            log.warn("chatCompletion parse error provider={} model={} err={}", providerKey, config.model, e.getMessage());
            result.setWarning("智能服务返回结果异常，请稍后重试");
            return result;
        }
    }

    public int estimatePromptTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        return (int) Math.ceil(text.length() * 1.2);
    }

    public int estimateCompletionTokens(String answer) {
        if (!StringUtils.hasText(answer)) {
            return 0;
        }
        return (int) Math.ceil(answer.length() * 1.2);
    }

    private ProviderConfig resolveProvider(String providerKey) {
        if (PROVIDER_DEEPSEEK.equalsIgnoreCase(providerKey)) {
            return new ProviderConfig(deepseekBaseUrl, deepseekApiKey, deepseekModel);
        }
        if (PROVIDER_OPENAI.equalsIgnoreCase(providerKey)) {
            return new ProviderConfig(fallbackBaseUrl, fallbackApiKey, fallbackModel);
        }
        if (PROVIDER_DOUBAO.equalsIgnoreCase(providerKey)) {
            return new ProviderConfig(doubaoBaseUrl, doubaoApiKey, doubaoModel);
        }
        return null;
    }

    private RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    private Map<String, String> buildMessage(String role, String content) {
        Map<String, String> msg = new HashMap<>();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private int extractUsageTokens(JsonNode usageNode, String primaryField, String fallbackField) {
        if (usageNode == null || usageNode.isMissingNode()) {
            return 0;
        }
        int value = usageNode.path(primaryField).asInt(0);
        if (value > 0) {
            return value;
        }
        return usageNode.path(fallbackField).asInt(0);
    }

    private ParsedAnswer parseModelAnswer(String content) {
        ParsedAnswer parsed = new ParsedAnswer();
        if (!StringUtils.hasText(content)) {
            return parsed;
        }

        String trimmed = content.trim();
        Matcher fenceMatcher = JSON_FENCE_PATTERN.matcher(trimmed);
        if (fenceMatcher.find()) {
            trimmed = fenceMatcher.group(1);
        }

        try {
            JsonNode node = objectMapper.readTree(trimmed);
            parsed.answer = node.path("answer").asText("");
            if (node.has("confidence") && node.path("confidence").isNumber()) {
                parsed.confidence = node.path("confidence").asDouble();
            }
            if (StringUtils.hasText(parsed.answer)) {
                return parsed;
            }
        } catch (Exception ignored) {
            // 非JSON内容按纯文本兜底
        }

        parsed.answer = trimmed;
        parsed.confidence = null;
        return parsed;
    }

    private static class ParsedAnswer {
        private String answer;
        private Double confidence;
    }

    private static class ProviderConfig {
        private final String baseUrl;
        private final String apiKey;
        private final String model;

        private ProviderConfig(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
        }
    }
}
