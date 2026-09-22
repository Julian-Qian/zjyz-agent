package com.zjyz.agent.dao;

import com.zjyz.agent.workspace.knowledge.AgentKnowledgeRecords;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentKnowledgeMapper {
    String SOURCE_COLUMNS = "source_id AS sourceId,scope_type AS scopeType,cid,project_id AS projectId," +
            "source_type AS sourceType,external_ref AS externalRef,title,description,domain_code AS domainCode,tags," +
            "authority_code AS authorityCode,version_no AS versionNo,active_version AS activeVersion," +
            "lifecycle_status AS lifecycleStatus,index_status AS indexStatus,original_file_name AS originalFileName," +
            "mime_type AS mimeType,file_size AS fileSize,object_key AS objectKey,content_checksum AS contentChecksum," +
            "parser_version AS parserVersion,embedding_model AS embeddingModel,embedding_dimensions AS embeddingDimensions," +
            "chunk_count AS chunkCount,error_code AS errorCode,error_message AS errorMessage,created_by AS createdBy," +
            "updated_by AS updatedBy,published_by AS publishedBy,created_at AS createdAt,updated_at AS updatedAt," +
            "published_at AS publishedAt,archived_at AS archivedAt";

    String CHUNK_COLUMNS = "c.chunk_id AS chunkId,c.source_id AS sourceId,c.source_version AS sourceVersion,c.cid," +
            "c.chunk_seq AS chunkSeq,c.heading_path AS headingPath,c.content,c.content_hash AS contentHash," +
            "c.token_count AS tokenCount,c.qdrant_collection AS qdrantCollection,c.qdrant_point_id AS qdrantPointId," +
            "c.status,c.metadata_json AS metadataJson,c.created_at AS createdAt,c.updated_at AS updatedAt," +
            "s.title AS sourceTitle,s.tags AS sourceTags,s.domain_code AS domainCode,s.authority_code AS authorityCode," +
            "s.description AS sourceDescription,s.lifecycle_status AS lifecycleStatus";

    @Insert("INSERT INTO agent_knowledge_source (source_id,scope_type,cid,project_id,source_type,external_ref,title,description," +
            "domain_code,tags,authority_code,version_no,active_version,lifecycle_status,index_status,original_file_name,mime_type," +
            "file_size,object_key,content_checksum,parser_version,embedding_model,embedding_dimensions,chunk_count,error_code,error_message," +
            "created_by,updated_by,published_by,created_at,updated_at,published_at,archived_at) VALUES (#{sourceId},#{scopeType},#{cid}," +
            "#{projectId},#{sourceType},#{externalRef},#{title},#{description},#{domainCode},#{tags},#{authorityCode},#{versionNo}," +
            "#{activeVersion},#{lifecycleStatus},#{indexStatus},#{originalFileName},#{mimeType},#{fileSize},#{objectKey}," +
            "#{contentChecksum},#{parserVersion},#{embeddingModel},#{embeddingDimensions},#{chunkCount},#{errorCode},#{errorMessage}," +
            "#{createdBy},#{updatedBy},#{publishedBy},#{createdAt},#{updatedAt},#{publishedAt},#{archivedAt})")
    int insertSource(AgentKnowledgeRecords.Source source);

    @Select("SELECT " + SOURCE_COLUMNS + " FROM agent_knowledge_source WHERE source_type<>'LEARNING' AND source_id=#{sourceId} AND " +
            "((scope_type='PLATFORM' AND #{allowPlatform}=1) OR cid=#{cid}) LIMIT 1")
    AgentKnowledgeRecords.Source selectSource(@Param("cid") String cid, @Param("sourceId") String sourceId,
                                               @Param("allowPlatform") boolean allowPlatform);

    @Select("SELECT " + SOURCE_COLUMNS + " FROM agent_knowledge_source WHERE source_id=#{sourceId} LIMIT 1")
    AgentKnowledgeRecords.Source selectSourceInternal(@Param("sourceId") String sourceId);

    @Select("SELECT " + SOURCE_COLUMNS + " FROM agent_knowledge_source WHERE cid=#{cid} AND source_type='UPLOAD' " +
            "AND external_ref=#{externalRef} LIMIT 1")
    AgentKnowledgeRecords.Source selectSourceByExternalRef(@Param("cid") String cid,
                                                            @Param("externalRef") String externalRef);

    @Select("SELECT " + SOURCE_COLUMNS + " FROM agent_knowledge_source WHERE scope_type='PLATFORM' " +
            "AND source_type=#{sourceType} AND external_ref=#{externalRef} LIMIT 1")
    AgentKnowledgeRecords.Source selectPlatformSource(@Param("sourceType") String sourceType,
                                                       @Param("externalRef") String externalRef);

    @Update("UPDATE agent_knowledge_source SET title=#{title},description=#{description},domain_code=#{domainCode},tags=#{tags}," +
            "authority_code=#{authorityCode},content_checksum=#{contentChecksum},version_no=#{versionNo},file_size=#{fileSize}," +
            "active_version=1,lifecycle_status='PUBLISHED',index_status='PROCESSING',error_code=NULL,error_message=NULL," +
            "updated_by='SYSTEM',updated_at=#{updatedAt} WHERE source_id=#{sourceId} AND scope_type='PLATFORM' AND version_no=#{versionNo}-1")
    int updatePlatformSourceForSync(AgentKnowledgeRecords.Source source);

    @Select("SELECT " + SOURCE_COLUMNS + " FROM agent_knowledge_source WHERE scope_type='PLATFORM' AND source_type=#{sourceType} AND lifecycle_status='PUBLISHED'")
    List<AgentKnowledgeRecords.Source> selectPublishedPlatformSources(@Param("sourceType") String sourceType);

    @Update("UPDATE agent_knowledge_source SET lifecycle_status='UNPUBLISHED',active_version=0,updated_at=#{now},updated_by='SYSTEM' " +
            "WHERE source_id=#{sourceId} AND scope_type='PLATFORM' AND version_no=#{version} AND lifecycle_status='PUBLISHED'")
    int retirePlatformSource(@Param("sourceId") String sourceId, @Param("version") int version, @Param("now") LocalDateTime now);

    @Select("SELECT " + SOURCE_COLUMNS + " FROM agent_knowledge_source WHERE source_type<>'LEARNING' AND cid=#{cid} " +
            "AND (#{keyword}='' OR title LIKE CONCAT('%',#{keyword},'%') OR description LIKE CONCAT('%',#{keyword},'%') " +
            "OR tags LIKE CONCAT('%',#{keyword},'%')) AND (#{domainCode}='' OR domain_code=#{domainCode}) " +
            "AND (#{lifecycleStatus}='' OR lifecycle_status=#{lifecycleStatus}) AND (#{indexStatus}='' OR index_status=#{indexStatus}) " +
            "ORDER BY updated_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<AgentKnowledgeRecords.Source> selectSources(@Param("cid") String cid, @Param("keyword") String keyword,
                                                      @Param("domainCode") String domainCode,
                                                      @Param("lifecycleStatus") String lifecycleStatus,
                                                      @Param("indexStatus") String indexStatus,
                                                      @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(1) FROM agent_knowledge_source WHERE source_type<>'LEARNING' AND cid=#{cid} " +
            "AND (#{keyword}='' OR title LIKE CONCAT('%',#{keyword},'%') OR description LIKE CONCAT('%',#{keyword},'%') " +
            "OR tags LIKE CONCAT('%',#{keyword},'%')) AND (#{domainCode}='' OR domain_code=#{domainCode}) " +
            "AND (#{lifecycleStatus}='' OR lifecycle_status=#{lifecycleStatus}) AND (#{indexStatus}='' OR index_status=#{indexStatus})")
    long countSources(@Param("cid") String cid, @Param("keyword") String keyword,
                      @Param("domainCode") String domainCode, @Param("lifecycleStatus") String lifecycleStatus,
                      @Param("indexStatus") String indexStatus);

    @Select("SELECT COUNT(1) FROM agent_knowledge_source WHERE cid=#{cid} AND content_checksum=#{checksum} " +
            "AND lifecycle_status<>'ARCHIVED'")
    int countDuplicate(@Param("cid") String cid, @Param("checksum") String checksum);

    @Update("UPDATE agent_knowledge_source SET index_status='PROCESSING',error_code=NULL,error_message=NULL,updated_at=#{now} " +
            "WHERE source_id=#{sourceId} AND version_no=#{version}")
    int markSourceProcessing(@Param("sourceId") String sourceId, @Param("version") int version,
                             @Param("now") LocalDateTime now);

    @Update("UPDATE agent_knowledge_source SET index_status='READY',parser_version=#{parserVersion},embedding_model=#{embeddingModel}," +
            "embedding_dimensions=#{dimensions},chunk_count=#{chunkCount},error_code=NULL,error_message=NULL,updated_at=#{now} " +
            "WHERE source_id=#{sourceId} AND version_no=#{version}")
    int markSourceReady(@Param("sourceId") String sourceId, @Param("version") int version,
                        @Param("parserVersion") String parserVersion, @Param("embeddingModel") String embeddingModel,
                        @Param("dimensions") int dimensions, @Param("chunkCount") int chunkCount,
                        @Param("now") LocalDateTime now);

    @Update("UPDATE agent_knowledge_source SET index_status='FAILED',error_code=#{errorCode},error_message=#{errorMessage},updated_at=#{now} " +
            "WHERE source_id=#{sourceId} AND version_no=#{version}")
    int markSourceFailed(@Param("sourceId") String sourceId, @Param("version") int version,
                         @Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
                         @Param("now") LocalDateTime now);

    @Update("UPDATE agent_knowledge_source SET lifecycle_status='PUBLISHED',published_by=#{uid},published_at=#{now}," +
            "updated_by=#{uid},updated_at=#{now} WHERE source_id=#{sourceId} AND cid=#{cid} AND version_no=#{expectedVersion} " +
            "AND index_status='READY' AND lifecycle_status IN ('DRAFT','UNPUBLISHED')")
    int publish(@Param("cid") String cid, @Param("sourceId") String sourceId, @Param("expectedVersion") int expectedVersion,
                @Param("uid") String uid, @Param("now") LocalDateTime now);

    @Update("UPDATE agent_knowledge_source SET lifecycle_status='UNPUBLISHED',updated_by=#{uid},updated_at=#{now} " +
            "WHERE source_id=#{sourceId} AND cid=#{cid} AND lifecycle_status='PUBLISHED'")
    int unpublish(@Param("cid") String cid, @Param("sourceId") String sourceId, @Param("uid") String uid,
                  @Param("now") LocalDateTime now);

    @Update("UPDATE agent_knowledge_source SET lifecycle_status='ARCHIVED',active_version=0,archived_at=#{now}," +
            "updated_by=#{uid},updated_at=#{now} WHERE source_id=#{sourceId} AND cid=#{cid} AND lifecycle_status<>'ARCHIVED'")
    int archive(@Param("cid") String cid, @Param("sourceId") String sourceId, @Param("uid") String uid,
                @Param("now") LocalDateTime now);

    @Update("UPDATE agent_knowledge_source SET index_status='PENDING',error_code=NULL,error_message=NULL,updated_by=#{uid},updated_at=#{now} " +
            "WHERE source_id=#{sourceId} AND cid=#{cid} AND lifecycle_status<>'ARCHIVED'")
    int markReindexPending(@Param("cid") String cid, @Param("sourceId") String sourceId, @Param("uid") String uid,
                           @Param("now") LocalDateTime now);

    @Delete("DELETE FROM agent_knowledge_chunk WHERE source_id=#{sourceId} AND source_version=#{version}")
    int deleteChunks(@Param("sourceId") String sourceId, @Param("version") int version);

    @Insert("INSERT INTO agent_knowledge_chunk (chunk_id,source_id,source_version,cid,chunk_seq,heading_path,content,content_hash," +
            "token_count,qdrant_collection,qdrant_point_id,status,metadata_json,created_at,updated_at) VALUES (#{chunkId},#{sourceId}," +
            "#{sourceVersion},#{cid},#{chunkSeq},#{headingPath},#{content},#{contentHash},#{tokenCount},#{qdrantCollection}," +
            "#{qdrantPointId},#{status},#{metadataJson},#{createdAt},#{updatedAt})")
    int insertChunk(AgentKnowledgeRecords.Chunk chunk);

    @Select("SELECT " + CHUNK_COLUMNS + " FROM agent_knowledge_chunk c JOIN agent_knowledge_source s ON s.source_id=c.source_id " +
            "WHERE c.source_id=#{sourceId} AND c.cid=#{cid} ORDER BY c.chunk_seq LIMIT #{limit} OFFSET #{offset}")
    List<AgentKnowledgeRecords.Chunk> selectChunks(@Param("cid") String cid, @Param("sourceId") String sourceId,
                                                   @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(1) FROM agent_knowledge_chunk WHERE source_id=#{sourceId} AND cid=#{cid}")
    long countChunks(@Param("cid") String cid, @Param("sourceId") String sourceId);

    @Select("SELECT " + CHUNK_COLUMNS + " FROM agent_knowledge_chunk c JOIN agent_knowledge_source s ON s.source_id=c.source_id " +
            "WHERE s.source_type<>'LEARNING' AND c.chunk_id=#{chunkId} AND c.status='ACTIVE' AND c.source_version=s.version_no AND s.active_version=1 AND s.lifecycle_status='PUBLISHED' " +
            "AND s.index_status='READY' AND (s.source_type<>'HELP_ARTICLE' OR EXISTS (SELECT 1 FROM help_article h WHERE CAST(h.help_article_id AS CHAR)=s.external_ref AND h.status=1 AND h.is_deleted=0 AND DATE_FORMAT(h.update_time,'%Y-%m-%d %H:%i:%s')=SUBSTRING_INDEX(s.description,';helpUpdatedAt=',-1))) AND (s.scope_type='PLATFORM' OR s.cid=#{cid}) LIMIT 1")
    AgentKnowledgeRecords.Chunk selectPublishedChunk(@Param("cid") String cid, @Param("chunkId") String chunkId);

    @Select("SELECT " + CHUNK_COLUMNS + " FROM agent_knowledge_chunk c JOIN agent_knowledge_source s ON s.source_id=c.source_id " +
            "WHERE s.source_type<>'LEARNING' AND c.status='ACTIVE' AND c.source_version=s.version_no AND s.active_version=1 AND s.lifecycle_status='PUBLISHED' AND s.index_status='READY' " +
            "AND (s.source_type<>'HELP_ARTICLE' OR EXISTS (SELECT 1 FROM help_article h WHERE CAST(h.help_article_id AS CHAR)=s.external_ref AND h.status=1 AND h.is_deleted=0 AND DATE_FORMAT(h.update_time,'%Y-%m-%d %H:%i:%s')=SUBSTRING_INDEX(s.description,';helpUpdatedAt=',-1))) AND (s.scope_type='PLATFORM' OR s.cid=#{cid}) AND (s.title LIKE CONCAT('%',#{keyword},'%') " +
            "OR s.description LIKE CONCAT('%',#{keyword},'%') OR s.tags LIKE CONCAT('%',#{keyword},'%') " +
            "OR c.content LIKE CONCAT('%',#{keyword},'%')) ORDER BY s.authority_code,c.chunk_seq LIMIT #{limit}")
    List<AgentKnowledgeRecords.Chunk> selectPublishedChunksByKeyword(@Param("cid") String cid,
                                                                     @Param("keyword") String keyword,
                                                                     @Param("limit") int limit);

    @Select("SELECT c.qdrant_point_id FROM agent_knowledge_chunk c JOIN agent_knowledge_source s ON s.source_id=c.source_id " +
            "WHERE s.source_type<>'LEARNING' AND c.status='ACTIVE' AND c.source_version=s.version_no AND s.active_version=1 " +
            "AND s.lifecycle_status='PUBLISHED' AND s.index_status='READY' AND s.scope_type=#{scopeType} " +
            "AND (s.scope_type='PLATFORM' OR s.cid=#{cid}) " +
            "AND (s.source_type<>'HELP_ARTICLE' OR EXISTS (SELECT 1 FROM help_article h WHERE CAST(h.help_article_id AS CHAR)=s.external_ref " +
            "AND h.status=1 AND h.is_deleted=0 AND DATE_FORMAT(h.update_time,'%Y-%m-%d %H:%i:%s')=SUBSTRING_INDEX(s.description,';helpUpdatedAt=',-1))) " +
            "AND (COALESCE(s.tags,'') NOT LIKE '%product-version:%' OR FIND_IN_SET(CONCAT('product-version:',#{productVersion}),s.tags)>0) " +
            "AND c.qdrant_point_id IS NOT NULL ORDER BY c.chunk_id LIMIT #{limit}")
    List<String> selectPublishedVectorPointIds(@Param("cid") String cid, @Param("scopeType") String scopeType, @Param("productVersion") String productVersion, @Param("limit") int limit);

    @Insert("INSERT INTO agent_knowledge_ingest_job (job_id,source_id,source_version,cid,job_type,status,attempt_count,next_retry_at," +
            "locked_by,locked_at,error_code,error_message,created_at,started_at,completed_at,updated_at) VALUES (#{jobId},#{sourceId}," +
            "#{sourceVersion},#{cid},#{jobType},#{status},#{attemptCount},#{nextRetryAt},#{lockedBy},#{lockedAt},#{errorCode}," +
            "#{errorMessage},#{createdAt},#{startedAt},#{completedAt},#{updatedAt})")
    int insertJob(AgentKnowledgeRecords.Job job);

    @Select("SELECT job_id AS jobId,source_id AS sourceId,source_version AS sourceVersion,cid,job_type AS jobType,status," +
            "attempt_count AS attemptCount,next_retry_at AS nextRetryAt,locked_by AS lockedBy,locked_at AS lockedAt,error_code AS errorCode," +
            "error_message AS errorMessage,created_at AS createdAt,started_at AS startedAt,completed_at AS completedAt,updated_at AS updatedAt " +
            "FROM agent_knowledge_ingest_job WHERE status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=#{now}) " +
            "ORDER BY created_at LIMIT 1")
    AgentKnowledgeRecords.Job selectPendingJob(@Param("now") LocalDateTime now);

    @Select("SELECT job_id AS jobId,source_id AS sourceId,source_version AS sourceVersion,cid,job_type AS jobType,status," +
            "attempt_count AS attemptCount,next_retry_at AS nextRetryAt,locked_by AS lockedBy,locked_at AS lockedAt,error_code AS errorCode," +
            "error_message AS errorMessage,created_at AS createdAt,started_at AS startedAt,completed_at AS completedAt,updated_at AS updatedAt " +
            "FROM agent_knowledge_ingest_job WHERE job_id=#{jobId} AND cid=#{cid} LIMIT 1")
    AgentKnowledgeRecords.Job selectJob(@Param("cid") String cid, @Param("jobId") String jobId);

    @Update("UPDATE agent_knowledge_ingest_job SET status='RUNNING',attempt_count=attempt_count+1,locked_by=#{worker},locked_at=#{now}," +
            "started_at=COALESCE(started_at,#{now}),updated_at=#{now} WHERE job_id=#{jobId} AND status='PENDING'")
    int claimJob(@Param("jobId") String jobId, @Param("worker") String worker, @Param("now") LocalDateTime now);

    @Update("UPDATE agent_knowledge_ingest_job SET status='SUCCEEDED',completed_at=#{now},updated_at=#{now},error_code=NULL,error_message=NULL " +
            "WHERE job_id=#{jobId} AND status='RUNNING'")
    int completeJob(@Param("jobId") String jobId, @Param("now") LocalDateTime now);

    @Update("UPDATE agent_knowledge_ingest_job SET status=#{status},next_retry_at=#{nextRetryAt},error_code=#{errorCode}," +
            "error_message=#{errorMessage},updated_at=#{now},completed_at=CASE WHEN #{status}='FAILED' THEN #{now} ELSE NULL END " +
            "WHERE job_id=#{jobId} AND status='RUNNING'")
    int failJob(@Param("jobId") String jobId, @Param("status") String status,
                @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("errorCode") String errorCode,
                @Param("errorMessage") String errorMessage, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(1) FROM agent_knowledge_ingest_job WHERE source_id=#{sourceId} AND source_version=#{version} " +
            "AND status IN ('PENDING','RUNNING')")
    int countActiveJobs(@Param("sourceId") String sourceId, @Param("version") int version);

    @Select("SELECT job_id AS jobId,source_id AS sourceId,source_version AS sourceVersion,cid,job_type AS jobType,status," +
            "attempt_count AS attemptCount,next_retry_at AS nextRetryAt,locked_by AS lockedBy,locked_at AS lockedAt,error_code AS errorCode," +
            "error_message AS errorMessage,created_at AS createdAt,started_at AS startedAt,completed_at AS completedAt,updated_at AS updatedAt " +
            "FROM agent_knowledge_ingest_job WHERE source_id=#{sourceId} AND source_version=#{version} " +
            "AND status IN ('PENDING','RUNNING') ORDER BY created_at DESC LIMIT 1")
    AgentKnowledgeRecords.Job selectLatestActiveJob(@Param("sourceId") String sourceId,
                                                     @Param("version") int version);

    @Update("UPDATE agent_knowledge_ingest_job SET status='PENDING',locked_by=NULL,locked_at=NULL,next_retry_at=#{now},updated_at=#{now} " +
            "WHERE status='RUNNING' AND locked_at<#{staleBefore}")
    int recoverStaleJobs(@Param("staleBefore") LocalDateTime staleBefore, @Param("now") LocalDateTime now);

    @Insert("INSERT INTO agent_run_knowledge_ref (run_id,message_id,cid,source_id,source_version,chunk_id,retrieval_method," +
            "rank_no,raw_score,final_score,cited,created_at) VALUES (#{runId},#{messageId},#{cid},#{sourceId},#{sourceVersion}," +
            "#{chunkId},#{retrievalMethod},#{rankNo},#{rawScore},#{finalScore},#{cited},#{createdAt})")
    int insertReference(AgentKnowledgeRecords.ReferenceAudit reference);
}
