package com.zjyz.agent.workspace.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentEmbeddingClient {
    private final ObjectMapper objectMapper;

    @Value("${agent.embedding.baseUrl:https://open.bigmodel.cn}")
    private String baseUrl;
    @Value("${agent.embedding.apiKey:${ZHIPU_API_KEY:}}")
    private String apiKey;
    @Value("${agent.embedding.model:embedding-3}")
    private String model;
    @Value("${agent.embedding.dimensions:1024}")
    private int dimensions;
    @Value("${agent.embedding.connectTimeoutMs:3000}")
    private int connectTimeoutMs;
    @Value("${agent.embedding.readTimeoutMs:15000}")
    private int readTimeoutMs;

    public AgentEmbeddingClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(baseUrl) && StringUtils.hasText(apiKey);
    }

    public String model() {
        return model;
    }

    public int dimensions() {
        return dimensions;
    }

    public List<List<Double>> embed(List<String> input) {
        if (CollectionUtils.isEmpty(input)) {
            return new ArrayList<>();
        }
        if (!isConfigured()) {
            throw new KnowledgeProcessingException("AKB006", "Embedding服务未配置");
        }
        if (input.size() > 64) {
            throw new KnowledgeProcessingException("AKB006", "单批Embedding文本不能超过64条");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("input", input);
            payload.put("dimensions", dimensions);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey.trim());
            String response = client().postForObject(trimSlash(baseUrl) + "/api/paas/v4/embeddings",
                    new HttpEntity<>(payload, headers), String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != input.size()) {
                throw new KnowledgeProcessingException("AKB006", "Embedding服务返回数量不匹配");
            }
            List<List<Double>> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                if (!embedding.isArray() || embedding.size() != dimensions) {
                    throw new KnowledgeProcessingException("AKB006", "Embedding服务返回维度不匹配");
                }
                List<Double> vector = new ArrayList<>(dimensions);
                for (JsonNode value : embedding) {
                    vector.add(value.asDouble());
                }
                vectors.add(vector);
            }
            return vectors;
        } catch (KnowledgeProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new KnowledgeProcessingException("AKB006", "Embedding服务调用失败", e);
        }
    }

    public List<Double> embedQuery(String query) {
        List<String> inputs = new ArrayList<>();
        inputs.add(query);
        return embed(inputs).get(0);
    }

    private RestTemplate client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(connectTimeoutMs, 500));
        factory.setReadTimeout(Math.max(readTimeoutMs, 1000));
        return new RestTemplate(factory);
    }

    private String trimSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
