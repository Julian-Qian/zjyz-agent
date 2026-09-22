package com.zjyz.agent.workspace.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class QdrantVectorStore {
    private final ObjectMapper objectMapper;

    @Value("${agent.knowledge.vectorEnabled:${AGENT_KNOWLEDGE_VECTOR_ENABLED:false}}")
    private boolean vectorEnabled;
    @Value("${agent.qdrant.url:${QDRANT_URL:}}")
    private String qdrantUrl;
    @Value("${agent.qdrant.apiKey:${QDRANT_API_KEY:}}")
    private String apiKey;
    @Value("${agent.qdrant.platformCollectionAlias:zjyz_agent_platform_current}")
    private String platformCollection;
    @Value("${agent.qdrant.tenantCollectionAlias:zjyz_agent_tenant_current}")
    private String tenantCollection;
    @Value("${agent.qdrant.connectTimeoutMs:2000}")
    private int connectTimeoutMs;
    @Value("${agent.qdrant.readTimeoutMs:3000}")
    private int readTimeoutMs;

    public QdrantVectorStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean isEnabledAndConfigured() {
        return vectorEnabled && StringUtils.hasText(qdrantUrl) && StringUtils.hasText(apiKey);
    }

    public String collectionFor(AgentKnowledgeRecords.Source source) {
        return source != null && "PLATFORM".equalsIgnoreCase(source.getScopeType())
                ? platformCollection : tenantCollection;
    }

    public void upsert(AgentKnowledgeRecords.Source source,
                       List<AgentKnowledgeRecords.Chunk> chunks,
                       List<List<Double>> vectors) {
        if (!isEnabledAndConfigured()) {
            throw new KnowledgeProcessingException("AKB007", "向量数据库未配置或未启用");
        }
        if (CollectionUtils.isEmpty(chunks) || chunks.size() != vectors.size()) {
            throw new KnowledgeProcessingException("AKB007", "向量写入数据不完整");
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            AgentKnowledgeRecords.Chunk chunk = chunks.get(i);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema_version", "1");
            payload.put("source_id", source.getSourceId());
            payload.put("source_version", source.getVersionNo());
            payload.put("chunk_id", chunk.getChunkId());
            payload.put("scope_type", source.getScopeType());
            payload.put("cid", source.getCid());
            payload.put("domain", source.getDomainCode());
            payload.put("authority", source.getAuthorityCode());
            payload.put("lifecycle_status", source.getLifecycleStatus());
            payload.put("chunk_seq", chunk.getChunkSeq());
            payload.put("title", source.getTitle());
            payload.put("content_hash", chunk.getContentHash());
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", chunk.getQdrantPointId());
            point.put("vector", vectors.get(i));
            point.put("payload", payload);
            points.add(point);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", points);
        request(HttpMethod.PUT, "/collections/" + collectionFor(source) + "/points?wait=true", body);
    }

    public List<AgentKnowledgeRecords.SearchHit> searchPlatform(List<Double> vector, int limit) {
        return search(platformCollection, vector, null, limit);
    }

    public List<AgentKnowledgeRecords.SearchHit> searchTenant(String cid, List<Double> vector, int limit) {
        if (!StringUtils.hasText(cid)) {
            return Collections.emptyList();
        }
        return search(tenantCollection, vector, cid, limit);
    }

    /** SQL-authorized current point IDs are applied before ANN top-k, never after only. */
    public List<AgentKnowledgeRecords.SearchHit> searchEligible(List<Double> vector, List<String> pointIds,
                                                               String scopeType, String cid, int limit) {
        if (pointIds == null || pointIds.isEmpty()) return Collections.emptyList();
        if (pointIds.size() > 5000) throw new KnowledgeProcessingException("AKB015", "当前知识范围超过向量检索预算");
        if (!"PLATFORM".equals(scopeType) && !StringUtils.hasText(cid)) return Collections.emptyList();
        return search("PLATFORM".equals(scopeType) ? platformCollection : tenantCollection,
                vector, "PLATFORM".equals(scopeType) ? null : cid, limit, pointIds);
    }

    public void deleteSource(AgentKnowledgeRecords.Source source) {
        if (!isEnabledAndConfigured() || source == null) {
            return;
        }
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("value", source.getSourceId());
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("key", "source_id");
        condition.put("match", match);
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("must", Collections.singletonList(condition));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filter", filter);
        request(HttpMethod.POST, "/collections/" + collectionFor(source) + "/points/delete?wait=true", body);
    }

    static Map<String,Object> searchFilter(String cid,List<String> pointIds) {
        List<Object> must=new ArrayList<>();
        if(StringUtils.hasText(cid))must.add(java.util.Map.of("key","cid","match",Collections.singletonMap("value",cid)));
        if(pointIds!=null)must.add(Collections.singletonMap("has_id",new ArrayList<>(pointIds)));
        return must.isEmpty()?Collections.emptyMap():Collections.singletonMap("must",must);
    }

    private List<AgentKnowledgeRecords.SearchHit> search(String collection, List<Double> vector, String cid, int limit) {
        return search(collection, vector, cid, limit, null);
    }

    private List<AgentKnowledgeRecords.SearchHit> search(String collection, List<Double> vector, String cid, int limit, List<String> pointIds) {
        if (!isEnabledAndConfigured() || CollectionUtils.isEmpty(vector)) {
            return Collections.emptyList();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vector", vector);
        body.put("limit", Math.max(1, Math.min(limit, 50)));
        body.put("with_payload", true);
        body.put("with_vector", false);
        Map<String,Object> filter=searchFilter(cid,pointIds);
        if(!filter.isEmpty())body.put("filter",filter);
        String response = request(HttpMethod.POST, "/collections/" + collection + "/points/search", body);
        try {
            JsonNode result = objectMapper.readTree(response).path("result");
            if (!result.isArray()) {
                return Collections.emptyList();
            }
            List<AgentKnowledgeRecords.SearchHit> hits = new ArrayList<>();
            for (JsonNode item : result) {
                JsonNode payload = item.path("payload");
                if (!payload.hasNonNull("chunk_id") || !payload.hasNonNull("source_id")) {
                    continue;
                }
                AgentKnowledgeRecords.SearchHit hit = new AgentKnowledgeRecords.SearchHit();
                hit.setPointId(item.path("id").asText());
                hit.setSourceId(payload.path("source_id").asText());
                hit.setSourceVersion(payload.path("source_version").asInt(1));
                hit.setChunkId(payload.path("chunk_id").asText());
                hit.setScore(item.path("score").asDouble());
                hit.setRetrievalMethod("VECTOR");
                hits.add(hit);
            }
            return hits;
        } catch (Exception e) {
            throw new KnowledgeProcessingException("AKB007", "向量检索响应解析失败", e);
        }
    }

    private String request(HttpMethod method, String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("api-key", apiKey.trim());
            ResponseEntity<String> response = client().exchange(trimSlash(qdrantUrl) + path, method,
                    new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new KnowledgeProcessingException("AKB007", "向量数据库请求失败");
            }
            return response.getBody() == null ? "{}" : response.getBody();
        } catch (KnowledgeProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new KnowledgeProcessingException("AKB007", "向量数据库不可用", e);
        }
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
