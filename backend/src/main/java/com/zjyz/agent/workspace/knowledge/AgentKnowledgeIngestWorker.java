package com.zjyz.agent.workspace.knowledge;

import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.common.util.CommonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class AgentKnowledgeIngestWorker {
    private static final int EMBEDDING_BATCH_SIZE = 32;

    private final AgentKnowledgeMapper mapper;
    private final AgentKnowledgeObjectStorage storage;
    private final AgentKnowledgeDocumentParser parser;
    private final AgentEmbeddingClient embeddingClient;
    private final QdrantVectorStore vectorStore;

    @Value("${agent.knowledge.managementEnabled:${AGENT_KNOWLEDGE_MANAGEMENT_ENABLED:false}}")
    private boolean managementEnabled;

    public AgentKnowledgeIngestWorker(AgentKnowledgeMapper mapper,
                                      AgentKnowledgeObjectStorage storage,
                                      AgentKnowledgeDocumentParser parser,
                                      AgentEmbeddingClient embeddingClient,
                                      QdrantVectorStore vectorStore) {
        this.mapper = mapper;
        this.storage = storage;
        this.parser = parser;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    @Scheduled(fixedDelayString = "${agent.knowledge.workerDelayMs:3000}")
    public void poll() {
        if (!managementEnabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        mapper.recoverStaleJobs(now.minusMinutes(15), now);
        AgentKnowledgeRecords.Job candidate = mapper.selectPendingJob(now);
        if (candidate == null || mapper.claimJob(candidate.getJobId(), "backend", now) != 1) {
            return;
        }
        AgentKnowledgeRecords.Job running = candidate;
        running.setAttemptCount((candidate.getAttemptCount() == null ? 0 : candidate.getAttemptCount()) + 1);
        try {
            if ("DELETE".equals(running.getJobType())) {
                delete(running);
            } else {
                index(running);
            }
            mapper.completeJob(running.getJobId(), LocalDateTime.now());
        } catch (Exception e) {
            handleFailure(running, e);
        }
    }

    private void index(AgentKnowledgeRecords.Job job) {
        AgentKnowledgeRecords.Source source = mapper.selectSourceInternal(job.getSourceId());
        if (source == null || !job.getSourceVersion().equals(source.getVersionNo())) {
            throw new KnowledgeProcessingException("AKB010", "知识来源不存在或版本已失效");
        }
        if ("ARCHIVED".equals(source.getLifecycleStatus())) {
            vectorStore.deleteSource(source);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        mapper.markSourceProcessing(source.getSourceId(), source.getVersionNo(), now);
        byte[] bytes = storage.get(source.getObjectKey());
        AgentKnowledgeDocumentParser.ParseResult parsed = parser.parse(source.getOriginalFileName(), source.getMimeType(), bytes);
        if (CollectionUtils.isEmpty(parsed.getChunks())) {
            throw new KnowledgeProcessingException("AKB014", "文档没有可索引分段");
        }

        mapper.deleteChunks(source.getSourceId(), source.getVersionNo());
        List<AgentKnowledgeRecords.Chunk> chunks = new ArrayList<>();
        for (AgentKnowledgeDocumentParser.ParsedChunk parsedChunk : parsed.getChunks()) {
            AgentKnowledgeRecords.Chunk chunk = new AgentKnowledgeRecords.Chunk();
            chunk.setChunkId("akc_" + CommonUtil.createUuid());
            chunk.setSourceId(source.getSourceId());
            chunk.setSourceVersion(source.getVersionNo());
            chunk.setCid(source.getCid());
            chunk.setChunkSeq(parsedChunk.getSeq());
            chunk.setHeadingPath(parsedChunk.getHeadingPath());
            chunk.setContent(parsedChunk.getContent());
            chunk.setContentHash(parsedChunk.getContentHash());
            chunk.setTokenCount(parsedChunk.getTokenCount());
            chunk.setQdrantCollection(vectorStore.collectionFor(source));
            chunk.setQdrantPointId(UUID.nameUUIDFromBytes((source.getSourceId() + ":" + source.getVersionNo()
                    + ":" + parsedChunk.getSeq()).getBytes(StandardCharsets.UTF_8)).toString());
            chunk.setStatus("ACTIVE");
            chunk.setCreatedAt(now);
            chunk.setUpdatedAt(now);
            mapper.insertChunk(chunk);
            chunks.add(chunk);
        }

        for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size());
            List<AgentKnowledgeRecords.Chunk> batch = new ArrayList<>(chunks.subList(start, end));
            List<String> texts = new ArrayList<>();
            for (AgentKnowledgeRecords.Chunk chunk : batch) {
                String prefix = chunk.getHeadingPath() == null ? "" : chunk.getHeadingPath() + "\n";
                texts.add(source.getTitle() + "\n" + prefix + chunk.getContent());
            }
            List<List<Double>> vectors = embeddingClient.embed(texts);
            vectorStore.upsert(source, batch, vectors);
        }
        mapper.markSourceReady(source.getSourceId(), source.getVersionNo(), AgentKnowledgeDocumentParser.PARSER_VERSION,
                embeddingClient.model(), embeddingClient.dimensions(), chunks.size(), LocalDateTime.now());
    }

    private void delete(AgentKnowledgeRecords.Job job) {
        AgentKnowledgeRecords.Source source = mapper.selectSourceInternal(job.getSourceId());
        if (source != null) {
            vectorStore.deleteSource(source);
        }
    }

    private void handleFailure(AgentKnowledgeRecords.Job job, Exception exception) {
        String code = exception instanceof KnowledgeProcessingException
                ? ((KnowledgeProcessingException) exception).getErrorCode() : "AKB004";
        String message = safeMessage(exception);
        boolean retryable = ("AKB006".equals(code) || "AKB007".equals(code) || "AKB016".equals(code))
                && job.getAttemptCount() < 3;
        LocalDateTime now = LocalDateTime.now();
        mapper.failJob(job.getJobId(), retryable ? "PENDING" : "FAILED",
                retryable ? now.plusSeconds((long) Math.pow(2, job.getAttemptCount()) * 10L) : null,
                code, message, now);
        mapper.markSourceFailed(job.getSourceId(), job.getSourceVersion(), code, message, now);
        log.warn("agent knowledge job failed, jobId={}, sourceId={}, code={}, retry={}",
                job.getJobId(), job.getSourceId(), code, retryable);
    }

    private String safeMessage(Exception exception) {
        String value = exception == null ? null : exception.getMessage();
        if (value == null || value.trim().isEmpty()) {
            return "知识处理失败";
        }
        String safe = value.replaceAll("(?i)(api[-_ ]?key|authorization|token|secret)[^\\s,;]*", "$1=[REDACTED]");
        return safe.length() <= 900 ? safe : safe.substring(0, 900);
    }
}
