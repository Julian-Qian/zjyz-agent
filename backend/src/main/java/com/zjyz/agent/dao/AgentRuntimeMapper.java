package com.zjyz.agent.dao;

import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentRuntimeMapper {
    @Select("SELECT workspace_id AS workspaceId,cid,scope_type AS scopeType,scope_key AS scopeKey,project_id AS projectId,project_business_type AS projectBusinessType,name,status," +
            "created_by AS createdBy,created_at AS createdAt,updated_at AS updatedAt FROM agent_workspace " +
            "WHERE cid=#{cid} AND project_id=#{projectId} LIMIT 1")
    AgentRuntimeRecords.Workspace selectWorkspaceByProject(@Param("cid") String cid, @Param("projectId") String projectId);

    @Select("SELECT workspace_id AS workspaceId,cid,scope_type AS scopeType,scope_key AS scopeKey,project_id AS projectId,project_business_type AS projectBusinessType,name,status," +
            "created_by AS createdBy,created_at AS createdAt,updated_at AS updatedAt FROM agent_workspace " +
            "WHERE cid=#{cid} AND scope_type=#{scopeType} AND scope_key=#{scopeKey} LIMIT 1")
    AgentRuntimeRecords.Workspace selectWorkspaceByScope(@Param("cid") String cid, @Param("scopeType") String scopeType,
                                                         @Param("scopeKey") String scopeKey);

    @Select("SELECT workspace_id AS workspaceId,cid,scope_type AS scopeType,scope_key AS scopeKey,project_id AS projectId,project_business_type AS projectBusinessType,name,status," +
            "created_by AS createdBy,created_at AS createdAt,updated_at AS updatedAt FROM agent_workspace " +
            "WHERE cid=#{cid} AND workspace_id=#{workspaceId} LIMIT 1")
    AgentRuntimeRecords.Workspace selectWorkspaceById(@Param("cid") String cid, @Param("workspaceId") String workspaceId);

    @Insert("INSERT INTO agent_workspace (workspace_id,cid,scope_type,scope_key,project_id,project_business_type,name,status,created_by,created_at,updated_at) " +
            "VALUES (#{workspaceId},#{cid},#{scopeType},#{scopeKey},#{projectId},#{projectBusinessType},#{name},#{status},#{createdBy},#{createdAt},#{updatedAt})")
    int insertWorkspace(AgentRuntimeRecords.Workspace workspace);

    @Select("SELECT thread_id AS threadId,workspace_id AS workspaceId,cid,owner_uid AS ownerUid,title,status,summary," +
            "summary_version AS summaryVersion,last_message_at AS lastMessageAt,created_at AS createdAt,updated_at AS updatedAt " +
            "FROM agent_thread WHERE cid=#{cid} AND owner_uid=#{ownerUid} AND workspace_id=#{workspaceId} AND status='ACTIVE' " +
            "ORDER BY COALESCE(last_message_at,created_at) DESC")
    List<AgentRuntimeRecords.AgentThread> selectThreads(@Param("cid") String cid, @Param("ownerUid") String ownerUid,
                                                        @Param("workspaceId") String workspaceId);

    @Select("SELECT thread_id AS threadId,workspace_id AS workspaceId,cid,owner_uid AS ownerUid,title,status,summary," +
            "summary_version AS summaryVersion,last_message_at AS lastMessageAt,created_at AS createdAt,updated_at AS updatedAt " +
            "FROM agent_thread WHERE cid=#{cid} AND thread_id=#{threadId} LIMIT 1")
    AgentRuntimeRecords.AgentThread selectThread(@Param("cid") String cid, @Param("threadId") String threadId);

    @Insert("INSERT INTO agent_thread (thread_id,workspace_id,cid,owner_uid,title,status,summary,summary_version,last_message_at,created_at,updated_at) " +
            "VALUES (#{threadId},#{workspaceId},#{cid},#{ownerUid},#{title},#{status},#{summary},#{summaryVersion},#{lastMessageAt},#{createdAt},#{updatedAt})")
    int insertThread(AgentRuntimeRecords.AgentThread thread);

    @Update("UPDATE agent_thread SET title=#{title},updated_at=#{updatedAt} WHERE thread_id=#{threadId} AND cid=#{cid} AND owner_uid=#{ownerUid} AND status='ACTIVE'")
    int updateThreadTitle(@Param("threadId") String threadId, @Param("cid") String cid, @Param("ownerUid") String ownerUid,
                          @Param("title") String title, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE agent_thread SET status='ARCHIVED',updated_at=#{updatedAt} WHERE thread_id=#{threadId} AND cid=#{cid} AND owner_uid=#{ownerUid} AND status='ACTIVE'")
    int archiveThread(@Param("threadId") String threadId, @Param("cid") String cid, @Param("ownerUid") String ownerUid,
                      @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE agent_thread SET last_message_at=#{messageAt},updated_at=#{messageAt} WHERE thread_id=#{threadId}")
    int touchThread(@Param("threadId") String threadId, @Param("messageAt") LocalDateTime messageAt);

    @Insert("INSERT INTO agent_message (message_id,thread_id,run_id,cid,role,content_type,content,metadata_json,token_count,created_at) " +
            "VALUES (#{messageId},#{threadId},#{runId},#{cid},#{role},#{contentType},#{content},#{metadataJson},#{tokenCount},#{createdAt})")
    int insertMessage(AgentRuntimeRecords.Message message);

    @Select("SELECT message_id AS messageId,thread_id AS threadId,run_id AS runId,cid,role,content_type AS contentType,content," +
            "metadata_json AS metadataJson,token_count AS tokenCount,created_at AS createdAt FROM agent_message " +
            "WHERE cid=#{cid} AND thread_id=#{threadId} ORDER BY created_at DESC LIMIT #{limit}")
    List<AgentRuntimeRecords.Message> selectRecentMessages(@Param("cid") String cid, @Param("threadId") String threadId,
                                                           @Param("limit") int limit);

    @Select("SELECT message_id AS messageId,thread_id AS threadId,run_id AS runId,cid,role,content_type AS contentType,content," +
            "metadata_json AS metadataJson,token_count AS tokenCount,created_at AS createdAt FROM agent_message " +
            "WHERE cid=#{cid} AND thread_id=#{threadId} AND run_id=#{runId} AND role='user' " +
            "ORDER BY created_at ASC LIMIT 1")
    AgentRuntimeRecords.Message selectRunUserMessage(@Param("cid") String cid,
                                                     @Param("threadId") String threadId,
                                                     @Param("runId") String runId);

    @Select("SELECT run_id AS runId,thread_id AS threadId,workspace_id AS workspaceId,cid,owner_uid AS ownerUid," +
            "client_request_id AS clientRequestId,status,model_provider AS modelProvider,model_name AS modelName," +
            "reasoning_effort AS reasoningEffort,iteration_count AS iterationCount,tool_call_count AS toolCallCount," +
            "prompt_tokens AS promptTokens,completion_tokens AS completionTokens,estimated_cost_cny AS estimatedCostCny," +
            "started_at AS startedAt,heartbeat_at AS heartbeatAt,completed_at AS completedAt,error_code AS errorCode," +
            "error_message AS errorMessage,created_at AS createdAt,updated_at AS updatedAt FROM agent_run " +
            "WHERE cid=#{cid} AND owner_uid=#{ownerUid} AND client_request_id=#{clientRequestId} LIMIT 1")
    AgentRuntimeRecords.Run selectRunByClientRequest(@Param("cid") String cid, @Param("ownerUid") String ownerUid,
                                                     @Param("clientRequestId") String clientRequestId);

    @Select("SELECT run_id AS runId,thread_id AS threadId,workspace_id AS workspaceId,cid,owner_uid AS ownerUid," +
            "client_request_id AS clientRequestId,status,model_provider AS modelProvider,model_name AS modelName," +
            "reasoning_effort AS reasoningEffort,iteration_count AS iterationCount,tool_call_count AS toolCallCount," +
            "prompt_tokens AS promptTokens,completion_tokens AS completionTokens,estimated_cost_cny AS estimatedCostCny," +
            "started_at AS startedAt,heartbeat_at AS heartbeatAt,completed_at AS completedAt,error_code AS errorCode," +
            "error_message AS errorMessage,created_at AS createdAt,updated_at AS updatedAt FROM agent_run WHERE run_id=#{runId} LIMIT 1")
    AgentRuntimeRecords.Run selectRun(@Param("runId") String runId);

    @Insert("INSERT INTO agent_run (run_id,thread_id,workspace_id,cid,owner_uid,client_request_id,status,model_provider,model_name," +
            "reasoning_effort,iteration_count,tool_call_count,prompt_tokens,completion_tokens,estimated_cost_cny,started_at,heartbeat_at," +
            "completed_at,error_code,error_message,created_at,updated_at) VALUES (#{runId},#{threadId},#{workspaceId},#{cid},#{ownerUid}," +
            "#{clientRequestId},#{status},#{modelProvider},#{modelName},#{reasoningEffort},#{iterationCount},#{toolCallCount},#{promptTokens}," +
            "#{completionTokens},#{estimatedCostCny},#{startedAt},#{heartbeatAt},#{completedAt},#{errorCode},#{errorMessage},#{createdAt},#{updatedAt})")
    int insertRun(AgentRuntimeRecords.Run run);

    @Update("UPDATE agent_run SET status=#{status},model_provider=#{modelProvider},model_name=#{modelName},reasoning_effort=#{reasoningEffort}," +
            "iteration_count=#{iterationCount},tool_call_count=#{toolCallCount},prompt_tokens=#{promptTokens},completion_tokens=#{completionTokens}," +
            "estimated_cost_cny=#{estimatedCostCny},started_at=#{startedAt},heartbeat_at=#{heartbeatAt},completed_at=#{completedAt}," +
            "error_code=#{errorCode},error_message=#{errorMessage},updated_at=#{updatedAt} WHERE run_id=#{runId} " +
            "AND status NOT IN ('COMPLETED','FAILED','CANCELLED','INTERRUPTED')")
    int updateRun(AgentRuntimeRecords.Run run);

    @Update("UPDATE agent_run SET heartbeat_at=#{now},updated_at=#{now} WHERE run_id=#{runId} " +
            "AND status IN ('PLANNING','RUNNING','FINALIZING')")
    int touchRunHeartbeat(@Param("runId") String runId, @Param("now") LocalDateTime now);

    @Select("SELECT run_id AS runId,thread_id AS threadId,workspace_id AS workspaceId,cid,owner_uid AS ownerUid," +
            "client_request_id AS clientRequestId,status,model_provider AS modelProvider,model_name AS modelName," +
            "reasoning_effort AS reasoningEffort,iteration_count AS iterationCount,tool_call_count AS toolCallCount," +
            "prompt_tokens AS promptTokens,completion_tokens AS completionTokens,estimated_cost_cny AS estimatedCostCny," +
            "started_at AS startedAt,heartbeat_at AS heartbeatAt,completed_at AS completedAt,error_code AS errorCode," +
            "error_message AS errorMessage,created_at AS createdAt,updated_at AS updatedAt FROM agent_run " +
            "WHERE status IN ('PLANNING','RUNNING','FINALIZING') " +
            "AND COALESCE(heartbeat_at,started_at,created_at)<#{cutoff} ORDER BY updated_at ASC LIMIT #{limit}")
    List<AgentRuntimeRecords.Run> selectStaleActiveRuns(@Param("cutoff") LocalDateTime cutoff,
                                                        @Param("limit") int limit);

    @Update("UPDATE agent_run SET status='FAILED',completed_at=#{now},heartbeat_at=#{now},updated_at=#{now}," +
            "error_code='AGT_RUN_TIMEOUT',error_message=#{message} WHERE run_id=#{runId} " +
            "AND status IN ('PLANNING','RUNNING','FINALIZING') AND COALESCE(heartbeat_at,started_at,created_at)<#{cutoff}")
    int timeoutRunIfStale(@Param("runId") String runId,
                          @Param("cutoff") LocalDateTime cutoff,
                          @Param("now") LocalDateTime now,
                          @Param("message") String message);

    @Update("UPDATE agent_run SET status='FINALIZING',heartbeat_at=#{now},updated_at=#{now} WHERE run_id=#{runId} " +
            "AND status IN ('PLANNING','RUNNING')")
    int claimRunForCompletion(@Param("runId") String runId, @Param("now") LocalDateTime now);

    @Update("UPDATE agent_run SET status='COMPLETED',model_provider=#{modelProvider},model_name=#{modelName}," +
            "iteration_count=#{iterationCount},tool_call_count=#{toolCallCount},prompt_tokens=#{promptTokens}," +
            "completion_tokens=#{completionTokens},estimated_cost_cny=#{estimatedCostCny},heartbeat_at=#{heartbeatAt}," +
            "completed_at=#{completedAt},error_code=NULL,error_message=NULL,updated_at=#{updatedAt} " +
            "WHERE run_id=#{runId} AND status='FINALIZING'")
    int completeRunIfFinalizing(AgentRuntimeRecords.Run run);

    @Select("SELECT COUNT(1) FROM agent_run WHERE cid=#{cid} AND owner_uid=#{ownerUid} AND status IN ('QUEUED','PLANNING','RUNNING','FINALIZING','WAITING_USER','WAITING_APPROVAL')")
    int countActiveRuns(@Param("cid") String cid, @Param("ownerUid") String ownerUid);

    @Update("UPDATE agent_run SET status='CANCELLED',completed_at=#{now},heartbeat_at=#{now},updated_at=#{now} WHERE run_id=#{runId} " +
            "AND cid=#{cid} AND owner_uid=#{ownerUid} AND status IN ('QUEUED','PLANNING','RUNNING','WAITING_USER','WAITING_APPROVAL')")
    int cancelRun(@Param("runId") String runId, @Param("cid") String cid, @Param("ownerUid") String ownerUid,
                  @Param("now") LocalDateTime now);

    @Update("UPDATE agent_run SET status='INTERRUPTED',completed_at=#{now},heartbeat_at=#{now},updated_at=#{now}," +
            "error_code='AGT_INTERRUPTED',error_message='服务重启，执行已中断' " +
            "WHERE status IN ('QUEUED','PLANNING','RUNNING','FINALIZING','WAITING_USER','WAITING_APPROVAL')")
    int interruptActiveRuns(@Param("now") LocalDateTime now);

    @Insert("INSERT INTO agent_step (step_id,run_id,seq_no,step_type,status,summary,input_summary,output_summary,error_code,error_message,started_at,completed_at) " +
            "VALUES (#{stepId},#{runId},#{seqNo},#{stepType},#{status},#{summary},#{inputSummary},#{outputSummary},#{errorCode},#{errorMessage},#{startedAt},#{completedAt})")
    int insertStep(AgentRuntimeRecords.Step step);

    @Update("UPDATE agent_step SET status=#{status},summary=#{summary},output_summary=#{outputSummary},error_code=#{errorCode}," +
            "error_message=#{errorMessage},completed_at=#{completedAt} WHERE step_id=#{stepId}")
    int updateStep(AgentRuntimeRecords.Step step);

    @Insert("INSERT INTO agent_tool_call (tool_call_id,run_id,step_id,call_ref,tool_code,tool_version,risk_level,arguments_json,arguments_hash," +
            "status,result_json,result_summary,started_at,completed_at,error_code) VALUES (#{toolCallId},#{runId},#{stepId},#{callRef},#{toolCode}," +
            "#{toolVersion},#{riskLevel},#{argumentsJson},#{argumentsHash},#{status},#{resultJson},#{resultSummary},#{startedAt},#{completedAt},#{errorCode})")
    int insertToolCall(AgentRuntimeRecords.ToolCall toolCall);

    @Update("UPDATE agent_tool_call SET status=#{status},result_json=#{resultJson},result_summary=#{resultSummary}," +
            "completed_at=#{completedAt},error_code=#{errorCode} WHERE tool_call_id=#{toolCallId}")
    int updateToolCall(AgentRuntimeRecords.ToolCall toolCall);

    @Insert("INSERT INTO agent_artifact (artifact_id,thread_id,run_id,workspace_id,cid,project_id,artifact_type,title,mime_type,storage_type,object_key," +
            "content_json,checksum,status,created_by,created_at) VALUES (#{artifactId},#{threadId},#{runId},#{workspaceId},#{cid},#{projectId},#{artifactType}," +
            "#{title},#{mimeType},#{storageType},#{objectKey},#{contentJson},#{checksum},#{status},#{createdBy},#{createdAt})")
    int insertArtifact(AgentRuntimeRecords.Artifact artifact);

    @Select("SELECT artifact_id AS artifactId,thread_id AS threadId,run_id AS runId,workspace_id AS workspaceId,cid,project_id AS projectId,artifact_type AS artifactType," +
            "title,mime_type AS mimeType,storage_type AS storageType,object_key AS objectKey,content_json AS contentJson,checksum,status," +
            "created_by AS createdBy,created_at AS createdAt FROM agent_artifact WHERE cid=#{cid} AND thread_id=#{threadId} ORDER BY created_at DESC LIMIT #{limit}")
    List<AgentRuntimeRecords.Artifact> selectArtifacts(@Param("cid") String cid, @Param("threadId") String threadId, @Param("limit") int limit);

    @Select("SELECT artifact_id AS artifactId,thread_id AS threadId,run_id AS runId,workspace_id AS workspaceId,cid,project_id AS projectId,artifact_type AS artifactType," +
            "title,mime_type AS mimeType,storage_type AS storageType,object_key AS objectKey,content_json AS contentJson,checksum,status," +
            "created_by AS createdBy,created_at AS createdAt FROM agent_artifact WHERE cid=#{cid} AND artifact_id=#{artifactId} LIMIT 1")
    AgentRuntimeRecords.Artifact selectArtifact(@Param("cid") String cid, @Param("artifactId") String artifactId);

    @Insert("INSERT INTO agent_run_event (event_id,run_id,seq_no,event_type,payload_json,visible_to_user,created_at) " +
            "VALUES (#{eventId},#{runId},#{seqNo},#{eventType},#{payloadJson},#{visibleToUser},#{createdAt})")
    int insertRunEvent(AgentRuntimeRecords.RunEvent event);

    @Select("SELECT COALESCE(MAX(seq_no),0) FROM agent_run_event WHERE run_id=#{runId}")
    int selectMaxEventSeq(@Param("runId") String runId);

    @Select("SELECT event_id AS eventId,run_id AS runId,seq_no AS seqNo,event_type AS eventType,payload_json AS payloadJson," +
            "visible_to_user AS visibleToUser,created_at AS createdAt FROM agent_run_event WHERE run_id=#{runId} AND seq_no>#{afterSeq} " +
            "AND visible_to_user=1 ORDER BY seq_no ASC LIMIT #{limit}")
    List<AgentRuntimeRecords.RunEvent> selectRunEvents(@Param("runId") String runId, @Param("afterSeq") int afterSeq, @Param("limit") int limit);
}
