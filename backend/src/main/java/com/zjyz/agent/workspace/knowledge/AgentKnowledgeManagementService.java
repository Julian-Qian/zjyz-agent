package com.zjyz.agent.workspace.knowledge;

import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.common.security.AuthContext;
import com.zjyz.common.util.CommonUtil;
import com.zjyz.membership.service.TenantMemberService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AgentKnowledgeManagementService {
    private static final Set<String> EXTENSIONS = java.util.Collections.unmodifiableSet(
            new java.util.LinkedHashSet<>(java.util.Arrays.asList("pdf", "docx", "txt", "md")));
    private static final Set<String> DOMAINS = java.util.Collections.unmodifiableSet(
            new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                    "GENERAL", "HELP", "PROJECT", "DOCUMENT", "SETTLEMENT", "FINANCE", "INVENTORY", "MATERIAL")));

    private final AgentKnowledgeMapper mapper;
    private final AgentKnowledgeObjectStorage storage;
    private final AgentKnowledgeDocumentParser parser;
    private final HybridAgentKnowledgeRetriever retriever;
    private final TenantMemberService tenantMemberService;
    private final QdrantVectorStore vectorStore;
    private final AgentEmbeddingClient embeddingClient;

    @Value("${agent.knowledge.managementEnabled:${AGENT_KNOWLEDGE_MANAGEMENT_ENABLED:false}}")
    private boolean managementEnabled;
    @Value("${agent.knowledge.maxFileBytes:20971520}")
    private long maxFileBytes;

    public AgentKnowledgeManagementService(AgentKnowledgeMapper mapper,
                                           AgentKnowledgeObjectStorage storage,
                                           AgentKnowledgeDocumentParser parser,
                                           HybridAgentKnowledgeRetriever retriever,
                                           TenantMemberService tenantMemberService,
                                           QdrantVectorStore vectorStore,
                                           AgentEmbeddingClient embeddingClient) {
        this.mapper = mapper;
        this.storage = storage;
        this.parser = parser;
        this.retriever = retriever;
        this.tenantMemberService = tenantMemberService;
        this.vectorStore = vectorStore;
        this.embeddingClient = embeddingClient;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> upload(MultipartFile file, String title, String description,
                                      String domain, String tags, String clientRequestId) {
        requireManagementOwner();
        String cid = requireCid();
        String uid = requireUid();
        String safeTitle = requireText(title, "知识标题不能为空", 255);
        if (file == null || file.isEmpty()) {
            throw biz("未选择上传文件", "AKB014");
        }
        if (file.getSize() > maxFileBytes) {
            throw biz("文件不能超过" + Math.max(1, maxFileBytes / 1024 / 1024) + "MB", "AKB002");
        }
        String originalFilename = safeFilename(file.getOriginalFilename());
        String extension = extension(originalFilename);
        if (!EXTENSIONS.contains(extension)) {
            throw biz("仅支持PDF、DOCX、TXT和Markdown文件", "AKB001");
        }
        String externalRef = StringUtils.hasText(clientRequestId) ? "upload:" + clip(clientRequestId.trim(), 100) : null;
        if (externalRef != null) {
            AgentKnowledgeRecords.Source existing = mapper.selectSourceByExternalRef(cid, externalRef);
            if (existing != null) {
                return uploadResult(existing, latestJobForResponse(existing));
            }
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw biz("文件读取失败", "AKB004");
        }
        parser.parse(originalFilename, file.getContentType(), bytes);
        String checksum = sha256(bytes);
        if (mapper.countDuplicate(cid, checksum) > 0) {
            throw biz("相同内容的知识文档已存在", "AKB003");
        }

        LocalDateTime now = LocalDateTime.now();
        AgentKnowledgeRecords.Source source = new AgentKnowledgeRecords.Source();
        source.setSourceId("aks_" + CommonUtil.createUuid());
        source.setScopeType("TENANT");
        source.setCid(cid);
        source.setSourceType("UPLOAD");
        source.setExternalRef(externalRef);
        source.setTitle(safeTitle);
        source.setDescription(clip(description, 1000));
        source.setDomainCode(normalizeDomain(domain));
        source.setTags(clip(tags, 1000));
        source.setAuthorityCode("TENANT_APPROVED");
        source.setVersionNo(1);
        source.setActiveVersion(1);
        source.setLifecycleStatus("DRAFT");
        source.setIndexStatus("PENDING");
        source.setOriginalFileName(originalFilename);
        source.setMimeType(clip(file.getContentType(), 128));
        source.setFileSize((long) bytes.length);
        source.setContentChecksum(checksum);
        source.setChunkCount(0);
        source.setCreatedBy(uid);
        source.setUpdatedBy(uid);
        source.setCreatedAt(now);
        source.setUpdatedAt(now);

        String objectKey = storage.put(cid, source.getSourceId(), 1, bytes);
        source.setObjectKey(objectKey);
        AgentKnowledgeRecords.Job job = newJob(source, "INDEX", now);
        try {
            mapper.insertSource(source);
            mapper.insertJob(job);
        } catch (RuntimeException e) {
            storage.deleteQuietly(objectKey);
            throw e;
        }
        return uploadResult(source, job);
    }

    public Map<String, Object> list(int pageNum, int pageSize, String keyword, String domain,
                                    String lifecycleStatus, String indexStatus) {
        requireManagementOwner();
        String cid = requireCid();
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String safeKeyword = normalize(keyword);
        String safeDomain = normalizeOptionalEnum(domain, DOMAINS);
        String safeLifecycle = normalizeOptionalEnum(lifecycleStatus,
                setOf("DRAFT", "PUBLISHED", "UNPUBLISHED", "ARCHIVED"));
        String safeIndex = normalizeOptionalEnum(indexStatus, setOf("PENDING", "PROCESSING", "READY", "FAILED"));
        long total = mapper.countSources(cid, safeKeyword, safeDomain, safeLifecycle, safeIndex);
        List<Map<String, Object>> items = mapper.selectSources(cid, safeKeyword, safeDomain, safeLifecycle, safeIndex,
                        (safePage - 1) * safeSize, safeSize).stream()
                .map(this::sourceView).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageNum", safePage);
        result.put("pageSize", safeSize);
        result.put("total", total);
        result.put("items", items);
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("published", mapper.countSources(cid, safeKeyword, safeDomain, "PUBLISHED", ""));
        statistics.put("draft", mapper.countSources(cid, safeKeyword, safeDomain, "DRAFT", "")
                + mapper.countSources(cid, safeKeyword, safeDomain, "UNPUBLISHED", ""));
        statistics.put("processing", mapper.countSources(cid, safeKeyword, safeDomain, "", "PENDING")
                + mapper.countSources(cid, safeKeyword, safeDomain, "", "PROCESSING"));
        statistics.put("failed", mapper.countSources(cid, safeKeyword, safeDomain, "", "FAILED"));
        result.put("statistics", statistics);
        return result;
    }

    public Map<String, Object> detail(String sourceId) {
        requireManagementOwner();
        return sourceView(requireTenantSource(sourceId));
    }

    public Map<String, Object> chunks(String sourceId, int pageNum, int pageSize) {
        requireManagementOwner();
        String cid = requireCid();
        requireTenantSource(sourceId);
        int safePage = Math.max(pageNum, 1);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        List<Map<String, Object>> items = mapper.selectChunks(cid, sourceId, (safePage - 1) * safeSize, safeSize)
                .stream().map(this::chunkView).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pageNum", safePage);
        result.put("pageSize", safeSize);
        result.put("total", mapper.countChunks(cid, sourceId));
        result.put("items", items);
        return result;
    }

    public Download download(String sourceId) {
        requireManagementOwner();
        AgentKnowledgeRecords.Source source = requireTenantSource(sourceId);
        return new Download(source.getOriginalFileName(), source.getMimeType(), storage.get(source.getObjectKey()));
    }

    public Map<String, Object> publish(String sourceId, Integer expectedVersion) {
        requireManagementOwner();
        AgentKnowledgeRecords.Source source = requireTenantSource(sourceId);
        int version = expectedVersion == null ? source.getVersionNo() : expectedVersion;
        int changed = mapper.publish(requireCid(), sourceId, version, requireUid(), LocalDateTime.now());
        if (changed != 1) {
            throw biz("文档尚未完成索引、版本已变化或状态不允许发布", "AKB008");
        }
        return sourceView(requireTenantSource(sourceId));
    }

    public Map<String, Object> unpublish(String sourceId) {
        requireManagementOwner();
        requireTenantSource(sourceId);
        if (mapper.unpublish(requireCid(), sourceId, requireUid(), LocalDateTime.now()) != 1) {
            throw biz("文档不是已发布状态", "AKB009");
        }
        return sourceView(requireTenantSource(sourceId));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reindex(String sourceId) {
        requireManagementOwner();
        AgentKnowledgeRecords.Source source = requireTenantSource(sourceId);
        if ("ARCHIVED".equals(source.getLifecycleStatus())) {
            throw biz("已归档文档不能重新索引", "AKB008");
        }
        if (mapper.countActiveJobs(sourceId, source.getVersionNo()) > 0) {
            return uploadResult(source, mapper.selectLatestActiveJob(sourceId, source.getVersionNo()));
        }
        LocalDateTime now = LocalDateTime.now();
        mapper.markReindexPending(requireCid(), sourceId, requireUid(), now);
        AgentKnowledgeRecords.Job job = newJob(source, "INDEX", now);
        mapper.insertJob(job);
        return uploadResult(requireTenantSource(sourceId), job);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> archive(String sourceId) {
        requireManagementOwner();
        AgentKnowledgeRecords.Source source = requireTenantSource(sourceId);
        LocalDateTime now = LocalDateTime.now();
        if (mapper.archive(requireCid(), sourceId, requireUid(), now) != 1) {
            throw biz("文档已归档", "AKB009");
        }
        if (mapper.countActiveJobs(sourceId, source.getVersionNo()) == 0) {
            mapper.insertJob(newJob(source, "DELETE", now));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", sourceId);
        result.put("lifecycleStatus", "ARCHIVED");
        return result;
    }

    public AgentKnowledgeRecords.SearchPreview preview(String query, Integer limit) {
        requireManagementOwner();
        String safeQuery = requireText(query, "检索问题不能为空", 500);
        return retriever.preview(safeQuery, limit == null ? 10 : Math.max(1, Math.min(limit, 10)));
    }

    public Map<String, Object> job(String jobId) {
        requireManagementOwner();
        AgentKnowledgeRecords.Job job = mapper.selectJob(requireCid(), jobId);
        if (job == null) {
            throw biz("处理任务不存在", "AKB010");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", job.getJobId());
        result.put("sourceId", job.getSourceId());
        result.put("jobType", job.getJobType());
        result.put("status", job.getStatus());
        result.put("attemptCount", job.getAttemptCount());
        result.put("errorCode", job.getErrorCode());
        result.put("errorMessage", job.getErrorMessage());
        result.put("updatedAt", job.getUpdatedAt());
        result.put("completedAt", job.getCompletedAt());
        return result;
    }

    public boolean manageAllowed() {
        String cid = AuthContext.getCid();
        String uid = AuthContext.getUid();
        return managementEnabled && StringUtils.hasText(cid) && StringUtils.hasText(uid)
                && "OWNER".equals(tenantMemberService.role(cid, uid));
    }

    public boolean vectorConfigured() {
        return vectorStore.isEnabledAndConfigured() && embeddingClient.isConfigured();
    }

    private AgentKnowledgeRecords.Source requireTenantSource(String sourceId) {
        AgentKnowledgeRecords.Source source = mapper.selectSource(requireCid(), sourceId, false);
        if (source == null || !"TENANT".equals(source.getScopeType())) {
            throw biz("知识文档不存在或无权访问", "AKB010");
        }
        return source;
    }

    private void requireManagementOwner() {
        if (!managementEnabled) {
            throw biz("AI知识库管理功能尚未启用", "AKB017");
        }
        String cid = requireCid();
        String uid = requireUid();
        if (!"OWNER".equals(tenantMemberService.role(cid, uid))) {
            throw biz("仅企业老板可管理AI知识库", "AKB011");
        }
    }

    private AgentKnowledgeRecords.Job newJob(AgentKnowledgeRecords.Source source, String type, LocalDateTime now) {
        AgentKnowledgeRecords.Job job = new AgentKnowledgeRecords.Job();
        job.setJobId("akj_" + CommonUtil.createUuid());
        job.setSourceId(source.getSourceId());
        job.setSourceVersion(source.getVersionNo());
        job.setCid(source.getCid());
        job.setJobType(type);
        job.setStatus("PENDING");
        job.setAttemptCount(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        return job;
    }

    private AgentKnowledgeRecords.Job latestJobForResponse(AgentKnowledgeRecords.Source source) {
        AgentKnowledgeRecords.Job job = new AgentKnowledgeRecords.Job();
        job.setJobId(null);
        job.setSourceId(source.getSourceId());
        job.setStatus(source.getIndexStatus());
        return job;
    }

    private Map<String, Object> uploadResult(AgentKnowledgeRecords.Source source, AgentKnowledgeRecords.Job job) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", source.getSourceId());
        result.put("version", source.getVersionNo());
        result.put("lifecycleStatus", source.getLifecycleStatus());
        result.put("indexStatus", source.getIndexStatus());
        result.put("jobId", job == null ? null : job.getJobId());
        return result;
    }

    private Map<String, Object> sourceView(AgentKnowledgeRecords.Source source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", source.getSourceId());
        result.put("title", source.getTitle());
        result.put("description", source.getDescription());
        result.put("domain", source.getDomainCode());
        result.put("tags", splitTags(source.getTags()));
        result.put("version", source.getVersionNo());
        result.put("lifecycleStatus", source.getLifecycleStatus());
        result.put("indexStatus", source.getIndexStatus());
        result.put("originalFileName", source.getOriginalFileName());
        result.put("mimeType", source.getMimeType());
        result.put("fileSize", source.getFileSize());
        result.put("chunkCount", source.getChunkCount());
        result.put("embeddingModel", source.getEmbeddingModel());
        result.put("embeddingDimensions", source.getEmbeddingDimensions());
        result.put("errorCode", source.getErrorCode());
        result.put("errorMessage", source.getErrorMessage());
        result.put("createdBy", source.getCreatedBy());
        result.put("createdAt", source.getCreatedAt());
        result.put("updatedAt", source.getUpdatedAt());
        result.put("publishedAt", source.getPublishedAt());
        return result;
    }

    private Map<String, Object> chunkView(AgentKnowledgeRecords.Chunk chunk) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunkId", chunk.getChunkId());
        result.put("chunkSeq", chunk.getChunkSeq());
        result.put("headingPath", chunk.getHeadingPath());
        result.put("content", chunk.getContent());
        result.put("tokenCount", chunk.getTokenCount());
        result.put("status", chunk.getStatus());
        return result;
    }

    private String requireUid() {
        String value = AuthContext.getUid();
        if (!StringUtils.hasText(value)) throw biz("登录状态已失效", "AUTH401");
        return value;
    }

    private String requireCid() {
        String value = AuthContext.getCid();
        if (!StringUtils.hasText(value)) throw biz("企业上下文无效", "AUTH401");
        return value;
    }

    private String requireText(String value, String message, int max) {
        String safe = normalize(value);
        if (!StringUtils.hasText(safe)) throw biz(message, "AKB014");
        return clip(safe, max);
    }

    private String normalizeDomain(String value) {
        String safe = normalize(value).toUpperCase(Locale.ROOT);
        return DOMAINS.contains(safe) ? safe : "GENERAL";
    }

    private String normalizeOptionalEnum(String value, Set<String> allowed) {
        String safe = normalize(value).toUpperCase(Locale.ROOT);
        return allowed.contains(safe) ? safe : "";
    }

    private Set<String> setOf(String... values) {
        return new java.util.LinkedHashSet<>(java.util.Arrays.asList(values));
    }

    private List<String> splitTags(String tags) {
        if (!StringUtils.hasText(tags)) return new ArrayList<>();
        return java.util.Arrays.stream(tags.replace('，', ',').split(","))
                .map(String::trim).filter(StringUtils::hasText).distinct().collect(Collectors.toList());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String clip(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        String safe = value.trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private String safeFilename(String value) {
        String safe = StringUtils.hasText(value) ? value.replace('\\', '/').trim() : "knowledge.txt";
        int slash = safe.lastIndexOf('/');
        if (slash >= 0) safe = safe.substring(slash + 1);
        safe = safe.replaceAll("[\\r\\n\\u0000]", "_");
        return StringUtils.hasText(safe) ? safe.substring(0, Math.min(safe.length(), 255)) : "knowledge.txt";
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MyBizException biz(String message, String code) {
        return new MyBizException(message, code);
    }

    public static class Download {
        private final String filename;
        private final String mimeType;
        private final byte[] bytes;

        public Download(String filename, String mimeType, byte[] bytes) {
            this.filename = filename;
            this.mimeType = mimeType;
            this.bytes = bytes;
        }

        public String getFilename() { return filename; }
        public String getMimeType() { return mimeType; }
        public byte[] getBytes() { return bytes; }
    }
}
