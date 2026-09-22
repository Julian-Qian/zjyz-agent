package com.zjyz.agent.workspace.knowledge;

import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.common.util.CommonUtil;
import com.zjyz.pojo.param.ret.HelpArticleBriefRet;
import com.zjyz.pojo.param.ret.HelpArticleDetailRet;
import com.zjyz.service.HelpCenterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
@Slf4j
public class PlatformKnowledgeSyncService {
    private final BuiltInAgentKnowledgeRetriever builtIn;
    private final HelpCenterService helpCenterService;
    private final AgentKnowledgeMapper mapper;
    private final AgentKnowledgeDocumentParser parser;
    private final AgentEmbeddingClient embeddingClient;
    private final QdrantVectorStore vectorStore;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${agent.knowledge.hybridEnabled:${AGENT_KNOWLEDGE_HYBRID_ENABLED:false}}")
    private boolean hybridEnabled;

    public PlatformKnowledgeSyncService(BuiltInAgentKnowledgeRetriever builtIn,
                                        HelpCenterService helpCenterService,
                                        AgentKnowledgeMapper mapper,
                                        AgentKnowledgeDocumentParser parser,
                                        AgentEmbeddingClient embeddingClient,
                                        QdrantVectorStore vectorStore) {
        this.builtIn = builtIn;
        this.helpCenterService = helpCenterService;
        this.mapper = mapper;
        this.parser = parser;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    @Scheduled(initialDelayString = "${agent.knowledge.platformSyncInitialDelayMs:60000}",
            fixedDelayString = "${agent.knowledge.platformSyncDelayMs:600000}")
    public void sync() {
        if (!hybridEnabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            Set<String> builtInRefs = new HashSet<>();
            for (AgentKnowledgeEntry entry : builtIn.trustedEntries()) {
                builtInRefs.add(entry.getId());
                syncOne("BUILTIN", entry.getId(), entry.getTitle(), entry.getContent(), entry.getDomain(),
                        entry.getAuthority(), String.join(",", entry.getKeywords()), entry.getSource());
            }
            retireMissing("BUILTIN", builtInRefs);
            List<HelpArticleBriefRet> briefs = helpCenterService.queryArticleList(null, null, "all", "all");
            Set<String> helpRefs = new HashSet<>();
            if (briefs == null) return; // No authoritative inventory: never retire on an unavailable response.
            for (HelpArticleBriefRet brief : briefs) helpRefs.add(String.valueOf(brief.getArticleId()));
            retireMissing("HELP_ARTICLE", helpRefs);
            if (!CollectionUtils.isEmpty(briefs)) {
                for (HelpArticleBriefRet brief : briefs) {
                    try {
                        HelpArticleDetailRet detail = helpCenterService.queryArticleDetail(brief.getArticleId());
                        String content = (StringUtils.hasText(detail.getArticleSummary()) ? detail.getArticleSummary() + "\n\n" : "")
                                + (detail.getArticleContent() == null ? "" : detail.getArticleContent());
                        String helpTags = CollectionUtils.isEmpty(detail.getTagList())
                                ? "" : String.join(",", detail.getTagList());
                        syncOne("HELP_ARTICLE", String.valueOf(detail.getArticleId()), detail.getArticleTitle(), content,
                                "HELP", "PUBLISHED_HELP", helpTags, "help_article:" + detail.getArticleId() + ";route=" + detail.getRouteScope()
                                        + ";business=" + detail.getBusinessType() + ";helpUpdatedAt=" + helpWatermark(detail));
                    } catch (Exception e) {
                        log.warn("failed to sync help article knowledge, articleId={}", brief.getArticleId());
                    }
                }
            }
        } finally {
            running.set(false);
        }
    }

    void syncOne(String sourceType, String externalRef, String title, String content,
                         String domain, String authority, String tags, String sourceDescription) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        String checksum = fingerprint(title, content, domain, authority, tags, sourceDescription);
        AgentKnowledgeRecords.Source source = mapper.selectPlatformSource(sourceType, externalRef);
        if (source != null && checksum.equals(source.getContentChecksum()) && "READY".equals(source.getIndexStatus())
                && "PUBLISHED".equals(source.getLifecycleStatus())
                && AgentKnowledgeDocumentParser.PARSER_VERSION.equals(source.getParserVersion())
                && (!(embeddingClient.isConfigured() && vectorStore.isEnabledAndConfigured())
                    || (java.util.Objects.equals(embeddingClient.model(), source.getEmbeddingModel())
                        && java.util.Objects.equals(embeddingClient.dimensions(), source.getEmbeddingDimensions())))) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (source == null) {
            source = new AgentKnowledgeRecords.Source();
            source.setSourceId("akp_" + sha256(sourceType + ":" + externalRef).substring(0, 32));
            source.setScopeType("PLATFORM");
            source.setSourceType(sourceType);
            source.setExternalRef(externalRef);
            source.setTitle(title);
            source.setDescription(sourceDescription);
            source.setDomainCode(StringUtils.hasText(domain) ? domain : "GENERAL");
            source.setTags(tags);
            source.setAuthorityCode(authority);
            source.setVersionNo(1);
            source.setActiveVersion(1);
            source.setLifecycleStatus("PUBLISHED");
            source.setIndexStatus("PROCESSING");
            source.setOriginalFileName(sourceType.toLowerCase() + "-" + externalRef + ".md");
            source.setMimeType("text/markdown");
            source.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
            source.setContentChecksum(checksum);
            source.setChunkCount(0);
            source.setCreatedBy("SYSTEM");
            source.setUpdatedBy("SYSTEM");
            source.setPublishedBy("SYSTEM");
            source.setCreatedAt(now);
            source.setUpdatedAt(now);
            source.setPublishedAt(now);
            try { mapper.insertSource(source); }
            catch (org.springframework.dao.DuplicateKeyException concurrentCreate) { return; }
        } else {
            source.setTitle(title);
            source.setDescription(sourceDescription);
            source.setDomainCode(StringUtils.hasText(domain) ? domain : "GENERAL");
            source.setTags(tags);
            source.setAuthorityCode(authority);
            source.setContentChecksum(checksum);
            source.setUpdatedAt(now);
            source.setVersionNo(source.getVersionNo() + 1);
            source.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
            if (mapper.updatePlatformSourceForSync(source) != 1) return;
            // Never mutate old chunks or delete old vector points: previous citations remain stable.
            // Retrieval validates source_version against the current SQL source version.

        }

        try {
            AgentKnowledgeDocumentParser.ParseResult parsed = parser.parse(source.getOriginalFileName(), "text/markdown",
                    content.getBytes(StandardCharsets.UTF_8));
            List<AgentKnowledgeRecords.Chunk> chunks = new ArrayList<>();
            for (AgentKnowledgeDocumentParser.ParsedChunk item : parsed.getChunks()) {
                AgentKnowledgeRecords.Chunk chunk = new AgentKnowledgeRecords.Chunk();
                chunk.setChunkId("akc_" + CommonUtil.createUuid());
                chunk.setSourceId(source.getSourceId());
                chunk.setSourceVersion(source.getVersionNo());
                chunk.setChunkSeq(item.getSeq());
                chunk.setHeadingPath(item.getHeadingPath());
                chunk.setContent(item.getContent());
                chunk.setContentHash(item.getContentHash());
                chunk.setTokenCount(item.getTokenCount());
                chunk.setQdrantCollection(vectorStore.collectionFor(source));
                chunk.setQdrantPointId(UUID.nameUUIDFromBytes((source.getSourceId() + ":" + source.getVersionNo()
                        + ":" + item.getSeq()).getBytes(StandardCharsets.UTF_8)).toString());
                chunk.setStatus("ACTIVE");
                chunk.setMetadataJson(new ObjectMapper().writeValueAsString(new java.util.LinkedHashMap<String,Object>() {{
                    put("title", title); put("description", sourceDescription); put("domain", domain);
                    put("authority", authority); put("tags", tags); put("sourceChecksum", checksum);
                }}));
                chunk.setCreatedAt(now);
                chunk.setUpdatedAt(now);
                mapper.insertChunk(chunk);
                chunks.add(chunk);
            }
            boolean vectorAvailable = embeddingClient.isConfigured() && vectorStore.isEnabledAndConfigured();
            for (int start = 0; vectorAvailable && start < chunks.size(); start += 32) {
                int end = Math.min(start + 32, chunks.size());
                List<AgentKnowledgeRecords.Chunk> batch = new ArrayList<>(chunks.subList(start, end));
                List<String> texts = batch.stream().map(item -> title + "\n" + item.getContent()).collect(Collectors.toList());
                vectorStore.upsert(source, batch, embeddingClient.embed(texts));
            }
            mapper.markSourceReady(source.getSourceId(), source.getVersionNo(), AgentKnowledgeDocumentParser.PARSER_VERSION,
                    vectorAvailable ? embeddingClient.model() : null, vectorAvailable ? embeddingClient.dimensions() : 0, chunks.size(), LocalDateTime.now());
        } catch (Exception e) {
            String code = e instanceof KnowledgeProcessingException
                    ? ((KnowledgeProcessingException) e).getErrorCode() : "AKB004";
            mapper.markSourceFailed(source.getSourceId(), source.getVersionNo(), code, "平台知识同步失败", LocalDateTime.now());
            log.warn("failed to sync platform knowledge, type={}, ref={}", sourceType, externalRef);
        }
    }

    void retireMissing(String type, Set<String> currentRefs) {
        List<AgentKnowledgeRecords.Source> sources = mapper.selectPublishedPlatformSources(type);
        if (sources == null) return;
        for (AgentKnowledgeRecords.Source source : sources) {
            if (!currentRefs.contains(source.getExternalRef())) {
                mapper.retirePlatformSource(source.getSourceId(), source.getVersionNo(), LocalDateTime.now());
            }
        }
    }

    static String helpWatermark(HelpArticleDetailRet detail) {
        return detail.getUpdateTime()==null ? "UNKNOWN" : detail.getUpdateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    String fingerprint(String... fields) {
        try { return sha256(new ObjectMapper().writeValueAsString(Arrays.asList(fields))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
