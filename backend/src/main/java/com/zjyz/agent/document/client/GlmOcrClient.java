package com.zjyz.agent.document.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.common.exception.MyBizException;
import lombok.Data;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class GlmOcrClient {
    @Value("${agent.documentIntake.glmOcr.enabled:${AGENT_DOCUMENT_GLM_OCR_ENABLED:true}}")
    private boolean enabled;

    @Value("${agent.documentIntake.glmOcr.baseUrl:${AGENT_DOCUMENT_GLM_OCR_BASE_URL:https://open.bigmodel.cn/api/paas/v4}}")
    private String baseUrl;

    @Value("${agent.documentIntake.glmOcr.apiKey:${GLM_OCR_API_KEY:${ZHIPU_API_KEY:${BIGMODEL_API_KEY:}}}}")
    private String apiKey;

    @Value("${agent.documentIntake.glmOcr.model:${AGENT_DOCUMENT_GLM_OCR_MODEL:glm-ocr}}")
    private String model;

    @Value("${agent.documentIntake.glmOcr.timeoutMs:${AGENT_DOCUMENT_GLM_OCR_TIMEOUT_MS:90000}}")
    private int timeoutMs;

    private final ObjectMapper objectMapper;

    public GlmOcrClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OcrResult parse(MultipartFile file) {
        if (!enabled) {
            throw new MyBizException("智能识别服务暂时不可用，请稍后重试", "AIDOC503");
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new MyBizException("智能识别服务暂时不可用，请联系管理员", "AIDOC503");
        }
        try {
            String contentType = StringUtils.hasText(file.getContentType())
                    ? file.getContentType()
                    : guessContentType(file.getOriginalFilename());
            String dataUrl = "data:" + contentType + ";base64,"
                    + Base64.getEncoder().encodeToString(file.getBytes());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("file", dataUrl);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());

            ResponseEntity<String> response = restTemplate().exchange(
                    normalizeBaseUrl(baseUrl) + "/layout_parsing",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            String responseBody = response.getBody() == null ? "" : response.getBody();
            if (!response.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(responseBody)) {
                throw new MyBizException("智能识别失败，请稍后重试", "AIDOC502");
            }
            return extractText(responseBody);
        } catch (MyBizException e) {
            throw e;
        } catch (Exception e) {
            throw new MyBizException("智能识别失败，请稍后重试", "AIDOC502");
        }
    }

    private OcrResult extractText(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        String text = firstNonBlank(
                root.path("md_results").asText(""),
                root.path("markdown").asText(""),
                root.path("content").asText(""),
                root.path("output_text").asText(""),
                root.path("data").path("md_results").asText(""),
                root.path("data").path("markdown").asText(""),
                root.path("data").path("content").asText(""),
                root.path("choices").path(0).path("message").path("content").asText("")
        );
        if (!StringUtils.hasText(text)) {
            text = findLongText(root);
        }
        OcrResult result = new OcrResult();
        result.setText(StringUtils.hasText(text) ? text : responseBody);
        result.setRawResponse(responseBody);
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode()) {
            result.setPromptTokens(usage.path("prompt_tokens").asInt(usage.path("input_tokens").asInt(0)));
            result.setCompletionTokens(usage.path("completion_tokens").asInt(usage.path("output_tokens").asInt(0)));
        }
        return result;
    }

    private String findLongText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            String value = node.asText("");
            return value.length() > 20 ? value : "";
        }
        String best = "";
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                String candidate = findLongText(child);
                if (candidate.length() > best.length()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(timeoutMs / 3, 3000));
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    private String normalizeBaseUrl(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "https://open.bigmodel.cn/api/paas/v4";
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private String guessContentType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    @Data
    public static class OcrResult {
        private String text;
        private String rawResponse;
        private int promptTokens;
        private int completionTokens;
    }
}
