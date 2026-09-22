package com.zjyz.agent.workspace.modelgateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Slf4j
public class GlmAgentModelGateway implements AgentModelGateway {
    @Value("${agent.runtime.model.enabled:true}")
    private boolean enabled;
    @Value("${agent.runtime.model.baseUrl:https://open.bigmodel.cn/api/paas/v4}")
    private String baseUrl;
    @Value("${agent.runtime.model.apiKey:${ZHIPU_API_KEY:}}")
    private String apiKey;
    @Value("${agent.runtime.model.primary:glm-5.2}")
    private String model;
    @Value("${agent.runtime.model.fallbackModels:glm-4.5-air}")
    private String fallbackModels;
    @Value("${agent.runtime.model.timeoutMs:120000}")
    private int timeoutMs;
    @Value("${agent.runtime.model.maxOutputTokens:4096}")
    private int maxOutputTokens;
    @Value("${agent.runtime.model.maxRetries:1}")
    private int maxRetries;

    @Value("${agent.v2.interpretation.maxOutputTokens:4096}")
    private int interpretationMaxTokens = 4096;
    @Value("${agent.v2.interpretation.thinking:false}")
    private boolean interpretationThinking = false;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public boolean isAvailable() {
        return enabled && StringUtils.hasText(apiKey) && !modelsInPriorityOrder().isEmpty();
    }

    @Override
    public ModelResult complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools, String reasoningEffort) {
        return completeInternal(messages, tools, reasoningEffort, false);
    }

    public ModelResult completeInterpretation(List<Map<String, Object>> messages) {
        return completeInternal(messages, java.util.Collections.emptyList(), "low", true);
    }

    private ModelResult completeInternal(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                        String reasoningEffort, boolean interpretation) {
        if (!isAvailable()) {
            ModelResult unavailable = new ModelResult();
            unavailable.setProvider("zhipu");
            unavailable.setErrorCode("MODEL_UNAVAILABLE");
            unavailable.setWarning("智能服务暂时不可用，请联系管理员");
            return unavailable;
        }
        ModelResult lastResult = null;
        for (String selectedModel : modelsInPriorityOrder()) {
            lastResult = interpretation
                    ? completeModel(selectedModel, messages, tools, reasoningEffort, true)
                    : completeModel(selectedModel, messages, tools, reasoningEffort);
            if (lastResult.isSuccess() || "OUTPUT_TRUNCATED".equals(lastResult.getErrorCode())
                    || "EMPTY_CONTENT".equals(lastResult.getErrorCode())) return lastResult;
            log.warn("GLM model unavailable, trying next candidate; model={}", selectedModel);
        }
        return lastResult;
    }

    ModelResult completeModel(String selectedModel, List<Map<String,Object>> messages,
                              List<Map<String,Object>> tools, String reasoningEffort) {
        return completeModel(selectedModel, messages, tools, reasoningEffort, false);
    }

    private ModelResult completeModel(String selectedModel, List<Map<String,Object>> messages,
                                      List<Map<String,Object>> tools, String reasoningEffort, boolean interpretation) {
        ModelResult result = new ModelResult();
        result.setProvider("zhipu");
        result.setModel(selectedModel);
        Exception lastError = null;
        for (int attempt = 0; attempt <= Math.max(maxRetries, 0); attempt++) {
            try {
            Map<String,Object> body = requestBody(selectedModel, messages, tools, reasoningEffort, interpretation);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Math.min(timeoutMs, 30000));
            factory.setReadTimeout(timeoutMs);
            RestTemplate restTemplate = new RestTemplate(factory);
            ResponseEntity<String> response = restTemplate.exchange(
                    normalizeBaseUrl(baseUrl) + "/chat/completions",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            if (!response.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(response.getBody())) {
                throw new IllegalStateException("模型服务未返回有效内容");
            }

            ModelResult decoded = decodeResponse(response.getBody(), interpretation);
            decoded.setModel(selectedModel);
            return decoded;
            } catch (Exception e) {
                lastError = e;
                log.warn("GLM workspace call failed, model={}, attempt={}, error={}", selectedModel, attempt + 1, e.getMessage());
            }
        }
        result.setErrorCode("MODEL_CALL_FAILED");
        result.setWarning(lastError == null ? "智能服务暂时不可用，请稍后重试" : "智能服务暂时不可用，已完成安全重试");
        return result;
    }

    Map<String,Object> requestBody(List<Map<String,Object>> messages, List<Map<String,Object>> tools,
                                   String reasoningEffort, boolean interpretation) {
        return requestBody(model, messages, tools, reasoningEffort, interpretation);
    }

    private Map<String,Object> requestBody(String selectedModel, List<Map<String,Object>> messages,
                                           List<Map<String,Object>> tools, String reasoningEffort, boolean interpretation) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", selectedModel);
            body.put("messages", messages);
            body.put("temperature", 0.1d);
            body.put("max_tokens", interpretation ? Math.max(512, interpretationMaxTokens) : maxOutputTokens);
            body.put("stream", false);
            if (interpretation) body.put("response_format", java.util.Collections.singletonMap("type", "json_object"));
            body.put("thinking", java.util.Collections.singletonMap("type", interpretation && !interpretationThinking ? "disabled" : "enabled"));
            if (supportsReasoningEffort(selectedModel) && (!interpretation || interpretationThinking)) {
                body.put("reasoning_effort", StringUtils.hasText(reasoningEffort) ? reasoningEffort : "high");
            }
            if (tools != null && !tools.isEmpty()) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }

        return body;
    }

    ModelResult decodeResponse(String responseBody, boolean interpretation) throws Exception {
        ModelResult result = new ModelResult();
        result.setProvider("zhipu");
        result.setModel(model);
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode messageNode = root.path("choices").path(0).path("message");
            result.setAssistantMessage(objectMapper.convertValue(messageNode, new TypeReference<Map<String, Object>>() { }));
            result.setContent(messageNode.path("content").asText(""));
            result.setPromptTokens(root.path("usage").path("prompt_tokens").asInt(0));
            result.setCompletionTokens(root.path("usage").path("completion_tokens").asInt(0));

            result.setFinishReason(root.path("choices").path(0).path("finish_reason").asText(""));
            if (interpretation && ("length".equals(result.getFinishReason()) || !StringUtils.hasText(result.getContent()))) {
                result.setErrorCode("length".equals(result.getFinishReason()) ? "OUTPUT_TRUNCATED" : "EMPTY_CONTENT");
                result.setWarning("本次理解结果未完整生成");
                return result;
            }
            List<ToolCall> calls = new ArrayList<>();
            JsonNode toolCallsNode = messageNode.path("tool_calls");
            if (toolCallsNode.isArray()) {
                for (JsonNode callNode : toolCallsNode) {
                    ToolCall call = new ToolCall();
                    call.setId(callNode.path("id").asText(""));
                    call.setName(callNode.path("function").path("name").asText(""));
                    call.setArguments(callNode.path("function").path("arguments").asText("{}"));
                    if (StringUtils.hasText(call.getName())) {
                        calls.add(call);
                    }
                }
            }
            result.setToolCalls(calls);
            result.setSuccess(true);
                return result;
    }

    List<String> modelsInPriorityOrder() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addModel(candidates, model);
        if (StringUtils.hasText(fallbackModels)) {
            for (String fallback : fallbackModels.split(",")) {
                addModel(candidates, fallback);
            }
        }
        return new ArrayList<>(candidates);
    }

    private void addModel(LinkedHashSet<String> candidates, String value) {
        if (StringUtils.hasText(value)) {
            candidates.add(value.trim());
        }
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    boolean supportsReasoningEffort(String modelCode) {
        String normalized = modelCode == null ? "" : modelCode.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("glm-5.2") || normalized.startsWith("glm-5.2-");
    }
}
