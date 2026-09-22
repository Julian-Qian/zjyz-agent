package com.zjyz.agent.workspace.v2.modelgateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * V2 OpenAI-compatible transport used by DeepSeek and the configured OpenAI fallback.
 * Requests are rebuilt from an allow-list so GLM-only fields never leak across providers.
 */
@Component
@Slf4j
public class AgentV2OpenAiCompatibleClient {
    static final String PROVIDER_DEEPSEEK = "deepseek";
    static final String PROVIDER_OPENAI = "openai";
    private static final Set<String> MESSAGE_ROLES = new LinkedHashSet<>(Arrays.asList(
            "system", "user", "assistant", "tool"));

    @Value("${agent.llm.enabled:true}")
    private boolean llmEnabled = true;
    @Value("${agent.llm.deepseek.baseUrl:https://api.deepseek.com}")
    private String deepseekBaseUrl = "https://api.deepseek.com";
    @Value("${agent.llm.deepseek.apiKey:}")
    private String deepseekApiKey = "";
    @Value("${agent.v2.model.deepseek.model:deepseek-v4-flash}")
    private String deepseekModel = "deepseek-v4-flash";
    @Value("${agent.llm.fallback.enabled:true}")
    private boolean openAiEnabled = true;
    @Value("${agent.llm.fallback.baseUrl:https://api.openai.com/v1}")
    private String openAiBaseUrl = "https://api.openai.com/v1";
    @Value("${agent.llm.fallback.apiKey:}")
    private String openAiApiKey = "";
    @Value("${agent.llm.fallback.model:gpt-4o-mini}")
    private String openAiModel = "gpt-4o-mini";
    @Value("${agent.v2.model.timeoutMs:120000}")
    private int timeoutMs = 120000;
    @Value("${agent.v2.model.maxOutputTokens:4096}")
    private int maxOutputTokens = 4096;

    private final ObjectMapper objectMapper;
    private final HttpTransport transport;

    @Autowired
    public AgentV2OpenAiCompatibleClient(ObjectMapper objectMapper) {
        this(objectMapper, new RestTemplateTransport());
    }

    AgentV2OpenAiCompatibleClient(ObjectMapper objectMapper, HttpTransport transport) {
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    public boolean isAvailable(String provider) {
        ProviderConfig config = provider(provider);
        return llmEnabled && config != null && config.enabled
                && StringUtils.hasText(config.baseUrl)
                && StringUtils.hasText(config.apiKey)
                && StringUtils.hasText(config.model);
    }

    public AgentModelGateway.ModelResult complete(String provider,
                                                   List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools) {
        ProviderConfig config = provider(provider);
        AgentModelGateway.ModelResult result = new AgentModelGateway.ModelResult();
        result.setProvider(normalizeProvider(provider));
        result.setModel(config == null ? null : config.model);
        if (!isAvailable(provider)) {
            result.setWarning("智能服务暂时不可用，请联系管理员");
            return result;
        }

        List<Map<String, Object>> safeMessages = sanitizeMessages(messages);
        if (safeMessages.isEmpty()) {
            result.setWarning("任务内容为空，请重新输入");
            return result;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.model);
        body.put("messages", safeMessages);
        body.put("temperature", 0.1d);
        body.put("max_tokens", Math.max(maxOutputTokens, 1));
        if (PROVIDER_DEEPSEEK.equals(normalizeProvider(provider))) {
            body.put("thinking", Collections.singletonMap("type", "disabled"));
        }
        List<Map<String, Object>> safeTools = sanitizeTools(tools);
        if (!safeTools.isEmpty()) {
            body.put("tools", safeTools);
            body.put("tool_choice", "auto");
        }

        try {
            HttpResponse response = transport.post(normalizeBaseUrl(config.baseUrl) + "/chat/completions",
                    body, config.apiKey.trim(), Math.max(timeoutMs, 1000));
            if (response == null || response.status < 200 || response.status >= 300
                    || !StringUtils.hasText(response.body)) {
                result.setWarning("智能服务暂时没有返回结果，请稍后重试");
                return result;
            }
            JsonNode root = objectMapper.readTree(response.body);
            JsonNode messageNode = root.path("choices").path(0).path("message");
            if (!messageNode.isObject()) {
                result.setWarning("智能服务返回结果异常，请稍后重试");
                return result;
            }
            List<AgentModelGateway.ToolCall> toolCalls = parseToolCalls(messageNode.path("tool_calls"));
            String content = messageNode.path("content").isNull()
                    ? "" : messageNode.path("content").asText("");
            if (!StringUtils.hasText(content) && toolCalls.isEmpty()) {
                result.setWarning("智能服务暂时没有返回结果，请稍后重试");
                return result;
            }
            result.setFinishReason(root.path("choices").path(0).path("finish_reason").asText(""));
            result.setAssistantMessage(sanitizeAssistantMessage(messageNode));
            result.setContent(content);
            result.setToolCalls(toolCalls);
            result.setPromptTokens(usage(root.path("usage"), "prompt_tokens", "input_tokens"));
            result.setCompletionTokens(usage(root.path("usage"), "completion_tokens", "output_tokens"));
            result.setSuccess(true);
            return result;
        } catch (HttpStatusCodeException error) {
            log.warn("V2 compatible model HTTP failure, provider={}, model={}, status={}",
                    result.getProvider(), result.getModel(), error.getStatusCode().value());
            result.setWarning("智能服务暂时不可用，请稍后重试");
            return result;
        } catch (Exception error) {
            log.warn("V2 compatible model call failed, provider={}, model={}, errorType={}",
                    result.getProvider(), result.getModel(), error.getClass().getSimpleName());
            result.setWarning("智能服务暂时不可用，请稍后重试");
            return result;
        }
    }

    private List<Map<String, Object>> sanitizeMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Map<String, Object> source : messages) {
            if (source == null) {
                continue;
            }
            String role = text(source.get("role"));
            if (!MESSAGE_ROLES.contains(role)) {
                continue;
            }
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", role);
            if (source.containsKey("content")) {
                message.put("content", source.get("content") == null ? null : text(source.get("content")));
            }
            List<Map<String, Object>> calls = sanitizeToolCalls(source.get("tool_calls"));
            if (!calls.isEmpty()) {
                message.put("tool_calls", calls);
            }
            copyText(source, message, "tool_call_id");
            copyText(source, message, "name");
            if (message.containsKey("content") || message.containsKey("tool_calls")) {
                sanitized.add(message);
            }
        }
        return sanitized;
    }

    private List<Map<String, Object>> sanitizeTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Map<String, Object> source : tools) {
            if (source == null || !(source.get("function") instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> sourceFunction = (Map<String, Object>) source.get("function");
            String name = text(sourceFunction.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", name);
            copyText(sourceFunction, function, "description");
            if (sourceFunction.containsKey("parameters")) {
                function.put("parameters", sourceFunction.get("parameters"));
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            sanitized.add(tool);
        }
        return sanitized;
    }

    private Map<String, Object> sanitizeAssistantMessage(JsonNode messageNode) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        if (messageNode.has("content")) {
            assistant.put("content", messageNode.path("content").isNull()
                    ? null : messageNode.path("content").asText(""));
        }
        List<Map<String, Object>> calls = sanitizeToolCallsNode(messageNode.path("tool_calls"));
        if (!calls.isEmpty()) {
            assistant.put("tool_calls", calls);
        }
        if (messageNode.hasNonNull("name") && StringUtils.hasText(messageNode.path("name").asText())) {
            assistant.put("name", messageNode.path("name").asText());
        }
        return assistant;
    }

    private List<AgentModelGateway.ToolCall> parseToolCalls(JsonNode node) {
        if (!node.isArray()) {
            return Collections.emptyList();
        }
        List<AgentModelGateway.ToolCall> calls = new ArrayList<>();
        for (JsonNode source : node) {
            String name = source.path("function").path("name").asText("");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            JsonNode argumentsNode = source.path("function").path("arguments");
            AgentModelGateway.ToolCall call = new AgentModelGateway.ToolCall();
            call.setId(source.path("id").asText(""));
            call.setName(name);
            call.setArguments(arguments(argumentsNode));
            calls.add(call);
        }
        return calls;
    }

    private List<Map<String, Object>> sanitizeToolCalls(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        JsonNode node = objectMapper.valueToTree(raw);
        return sanitizeToolCallsNode(node);
    }

    private List<Map<String, Object>> sanitizeToolCallsNode(JsonNode node) {
        if (!node.isArray()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (JsonNode source : node) {
            String name = source.path("function").path("name").asText("");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", name);
            JsonNode arguments = source.path("function").path("arguments");
            function.put("arguments", arguments(arguments));
            Map<String, Object> call = new LinkedHashMap<>();
            if (source.hasNonNull("id")) {
                call.put("id", source.path("id").asText());
            }
            call.put("type", "function");
            call.put("function", function);
            sanitized.add(call);
        }
        return sanitized;
    }

    private int usage(JsonNode usage, String primary, String fallback) {
        int value = usage.path(primary).asInt(0);
        return Math.max(value > 0 ? value : usage.path(fallback).asInt(0), 0);
    }

    private String arguments(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "{}";
        }
        String value = node.isTextual() ? node.asText() : node.toString();
        return StringUtils.hasText(value) ? value : "{}";
    }

    private ProviderConfig provider(String provider) {
        String normalized = normalizeProvider(provider);
        if (PROVIDER_DEEPSEEK.equals(normalized)) {
            return new ProviderConfig(true, deepseekBaseUrl, deepseekApiKey, deepseekModel);
        }
        if (PROVIDER_OPENAI.equals(normalized)) {
            return new ProviderConfig(openAiEnabled, openAiBaseUrl, openAiApiKey, openAiModel);
        }
        return null;
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void copyText(Map<String, Object> source, Map<String, Object> target, String key) {
        String value = text(source.get(key));
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    interface HttpTransport {
        HttpResponse post(String url, Map<String, Object> body, String apiKey, int timeoutMs);
    }

    static class HttpResponse {
        private final int status;
        private final String body;

        HttpResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static class RestTemplateTransport implements HttpTransport {
        @Override
        public HttpResponse post(String url, Map<String, Object> body, String apiKey, int timeoutMs) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Math.min(timeoutMs, 30000));
            factory.setReadTimeout(timeoutMs);
            ResponseEntity<String> response = new RestTemplate(factory).exchange(url, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);
            return new HttpResponse(response.getStatusCodeValue(), response.getBody());
        }
    }

    private static class ProviderConfig {
        private final boolean enabled;
        private final String baseUrl;
        private final String apiKey;
        private final String model;

        private ProviderConfig(boolean enabled, String baseUrl, String apiKey, String model) {
            this.enabled = enabled;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.model = model;
        }
    }
}
