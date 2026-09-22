package com.zjyz.agent.workspace.knowledge;

import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.agent.workspace.context.AgentTaskFrame;
import com.zjyz.common.security.AuthContext;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Primary
public class HybridAgentKnowledgeRetriever implements AgentKnowledgeRetriever {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9]{2,12}");
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(Arrays.asList(
            "我们", "现在", "这个", "那个", "请问", "一下", "怎么", "如何", "哪些", "什么", "是否", "可以"
    ));

    private final BuiltInAgentKnowledgeRetriever builtIn;
    private final AgentKnowledgeMapper mapper;
    private final AgentEmbeddingClient embeddingClient;
    private final QdrantVectorStore vectorStore;

    @Value("${agent.knowledge.hybridEnabled:${AGENT_KNOWLEDGE_HYBRID_ENABLED:false}}")
    private boolean hybridEnabled;
    @Value("${agent.guidance.productVersion:${AGENT_PRODUCT_VERSION:}}")
    private String productVersion = "";
    @Value("${agent.knowledge.minimumVectorScore:0.60}") private double minimumVectorScore=0.60d;

    public HybridAgentKnowledgeRetriever(BuiltInAgentKnowledgeRetriever builtIn,
                                         AgentKnowledgeMapper mapper,
                                         AgentEmbeddingClient embeddingClient,
                                         QdrantVectorStore vectorStore) {
        this.builtIn = builtIn;
        this.mapper = mapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    @Override
    public AgentKnowledgeContext retrieve(AgentTaskFrame taskFrame, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 6));
        AgentKnowledgeContext base = builtIn.retrieve(taskFrame, safeLimit);
        if (!hybridEnabled) {
            return base;
        }
        base.setMode("HYBRID_VECTOR");
        if (taskFrame == null || !StringUtils.hasText(taskFrame.getUserGoal())) {
            return base;
        }
        String cid = AuthContext.getCid();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (AgentKnowledgeEntry entry : base.getEntries()) {
            double relevance=lexicalRelevance(taskFrame.getUserGoal(),entry.getTitle(),entry.getContent());
            if(relevance>=0.35d) merge(candidates,entry,relevance);
        }

        for (String keyword : keywords(taskFrame.getUserGoal())) {
            List<AgentKnowledgeRecords.Chunk> rows = mapper.selectPublishedChunksByKeyword(cid, keyword, 8);
            for (int i = 0; i < rows.size(); i++) {
                if (!versionApplies(rows.get(i))) continue;
                AgentKnowledgeEntry entry = fromChunk(rows.get(i), "KEYWORD", 0.75d - (i * 0.01d));
                double relevance=lexicalRelevance(taskFrame.getUserGoal(),entry.getTitle(),entry.getContent());
                if(relevance>=0.35d) merge(candidates,entry,relevance);
            }
        }

        if (embeddingClient.isConfigured() && vectorStore.isEnabledAndConfigured()) {
            try {
                List<Double> vector = embeddingClient.embedQuery(taskFrame.getUserGoal());
                List<AgentKnowledgeRecords.SearchHit> hits = new ArrayList<>();
                List<String> platformIds=mapper.selectPublishedVectorPointIds(cid,"PLATFORM",productVersion,5001);
                List<String> tenantIds=mapper.selectPublishedVectorPointIds(cid,"TENANT",productVersion,5001);
                hits.addAll(vectorStore.searchEligible(vector,platformIds,"PLATFORM",cid,12));
                hits.addAll(vectorStore.searchEligible(vector,tenantIds,"TENANT",cid,12));
                for (AgentKnowledgeRecords.SearchHit hit : hits) {
                    AgentKnowledgeRecords.Chunk chunk = mapper.selectPublishedChunk(cid, hit.getChunkId());
                    if (chunk == null || !versionApplies(chunk) || !hit.getSourceId().equals(chunk.getSourceId())
                            || !hit.getSourceVersion().equals(chunk.getSourceVersion())) {
                        continue;
                    }
                    if (!Double.isFinite(hit.getScore()) || hit.getScore()<minimumVectorScore) continue;
                    AgentKnowledgeEntry entry = fromChunk(chunk, "VECTOR", hit.getScore());
                    merge(candidates, entry, Math.min(1d,hit.getScore()));
                }
            } catch (Exception e) {
                base.setDegraded(true);
                base.getWarnings().add("向量检索暂不可用，已降级为结构化和关键词知识检索。");
            }
        } else {
            base.setDegraded(true);
            base.getWarnings().add("向量检索未启用，当前使用结构化和关键词知识检索。");
        }

        List<AgentKnowledgeEntry> selected = candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::getScore)
                        .thenComparingDouble(c -> authorityBoost(c.getEntry().getAuthority())).reversed())
                .limit(safeLimit)
                .map(Candidate::getEntry)
                .collect(Collectors.toList());
        base.setEntries(selected);
        base.setReferences(selected.stream().map(AgentKnowledgeReference::from).collect(Collectors.toList()));
        Set<String> hints = new LinkedHashSet<>();
        selected.forEach(entry -> hints.addAll(entry.getToolHints()));
        base.setToolHints(hints);
        return base;
    }

    public AgentKnowledgeRecords.SearchPreview preview(String query, int limit) {
        AgentKnowledgeRecords.SearchPreview result = new AgentKnowledgeRecords.SearchPreview();
        result.setMode("HYBRID_VECTOR");
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setUserGoal(query);
        frame.setDomain("HELP");
        AgentKnowledgeContext context = retrieve(frame, Math.max(1, Math.min(limit, 10)));
        result.setDegraded(context.isDegraded());
        result.setWarning(String.join("；", context.getWarnings()));
        for (AgentKnowledgeEntry entry : context.getEntries()) {
            AgentKnowledgeRecords.SearchPreviewItem item = new AgentKnowledgeRecords.SearchPreviewItem();
            item.setSourceId(entry.getSourceId());
            item.setChunkId(entry.getChunkId());
            item.setTitle(entry.getTitle());
            item.setHeadingPath("");
            item.setExcerpt(clip(entry.getContent(), 240));
            item.setRetrievalMethod(entry.getRetrievalMethod() == null ? "STRUCTURED" : entry.getRetrievalMethod());
            item.setScore(entry.getScore() == null ? 1d : entry.getScore());
            result.getItems().add(item);
        }
        return result;
    }

    boolean versionApplies(AgentKnowledgeRecords.Chunk chunk) {
        if (chunk.getSourceTags() == null) return true;
        for (String tag : chunk.getSourceTags().split(",")) {
            if (tag.trim().startsWith("product-version:")) {
                return !productVersion.isEmpty() && tag.trim().equals("product-version:" + productVersion);
            }
        }
        return true;
    }

    private void merge(Map<String, Candidate> candidates, AgentKnowledgeEntry entry, double score) {
        String key = StringUtils.hasText(entry.getChunkId()) ? entry.getChunkId() : entry.getId();
        Candidate current = candidates.get(key);
        if (current == null || score > current.score) {
            candidates.put(key, new Candidate(entry, score));
        }
    }

    private AgentKnowledgeEntry fromChunk(AgentKnowledgeRecords.Chunk chunk, String method, double score) {
        AgentKnowledgeEntry entry = new AgentKnowledgeEntry();
        entry.setId("knowledge." + chunk.getChunkId());
        entry.setTitle(chunk.getSourceTitle());
        entry.setType("TENANT_KNOWLEDGE");
        entry.setDomain(chunk.getDomainCode());
        entry.setAuthority(chunk.getAuthorityCode());
        entry.setStatus("APPROVED");
        entry.setSource(chunk.getSourceTitle());
        entry.setContent(chunk.getContent());
        entry.setSourceId(chunk.getSourceId());
        entry.setSourceVersion(chunk.getSourceVersion());
        entry.setChunkId(chunk.getChunkId());
        entry.setRetrievalMethod(method);
        entry.setScore(score);
        return entry;
    }

    private List<String> keywords(String query) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String known : Arrays.asList("项目", "租出", "归还", "赔偿", "租入", "退租", "对账", "库存", "材料", "合同", "导入")) {
            if (query.contains(known)) {
                values.add(known);
            }
        }
        Matcher matcher = TOKEN_PATTERN.matcher(query);
        while (matcher.find() && values.size() < 4) {
            String value = matcher.group().toLowerCase(Locale.ROOT);
            if (!STOP_WORDS.contains(value)) {
                values.add(value);
            }
        }
        return new ArrayList<>(values).subList(0, Math.min(values.size(), 4));
    }

    static double lexicalRelevance(String query,String title,String content) {
        Set<String> requested=grams(normalizeQuery(query));
        if(requested.isEmpty()) return 0d;
        Set<String> titleTerms=grams(normalizeQuery(title));
        Set<String> bodyTerms=grams(normalizeQuery(content));
        long titleHits=requested.stream().filter(titleTerms::contains).count();
        long bodyHits=requested.stream().filter(bodyTerms::contains).count();
        return Math.max((double)titleHits/requested.size(),0.65d*bodyHits/requested.size());
    }
    private static String normalizeQuery(String value) {
        return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("怎么|如何|请问|一下|是什么|哪些|的|了|吗|[\\p{Punct}\\s？。，！]","");
    }
    private static Set<String> grams(String text) {
        Set<String> result=new LinkedHashSet<>();
        for(int i=0;i+2<=text.length();i++)result.add(text.substring(i,i+2));
        return result;
    }

    private double authorityBoost(String authority) {
        if ("RUNTIME_CODE".equalsIgnoreCase(authority)) return 0.03d;
        if ("APPROVED_SPEC".equalsIgnoreCase(authority)) return 0.02d;
        if ("PUBLISHED_HELP".equalsIgnoreCase(authority)) return 0.01d;
        return 0d;
    }

    private String clip(String text, int max) {
        if (!StringUtils.hasText(text) || text.length() <= max) return text;
        return text.substring(0, max - 1) + "…";
    }

    private static class Candidate {
        private final AgentKnowledgeEntry entry;
        private final double score;

        private Candidate(AgentKnowledgeEntry entry, double score) {
            this.entry = entry;
            this.score = score;
        }

        private AgentKnowledgeEntry getEntry() {
            return entry;
        }

        private double getScore() {
            return score;
        }
    }
}
