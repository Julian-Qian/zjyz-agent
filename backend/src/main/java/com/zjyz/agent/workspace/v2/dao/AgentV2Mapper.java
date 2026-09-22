package com.zjyz.agent.workspace.v2.dao;

import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AgentV2Mapper {
    String TASK_COLUMNS = "task_id AS taskId,turn_id AS turnId,parent_task_id AS parentTaskId,thread_id AS threadId," +
            "workspace_id AS workspaceId,latest_run_id AS latestRunId,cid,owner_uid AS ownerUid," +
            "client_request_id AS clientRequestId,request_fingerprint AS requestFingerprint,status," +
            "dialogue_act AS dialogueAct,relation_type AS relationType,goal," +
            "interpretation_json AS interpretationJson,task_spec_json AS taskSpecJson,scope_json AS scopeJson," +
            "context_json AS contextJson,capability_snapshot_json AS capabilitySnapshotJson,version," +
            "created_at AS createdAt,updated_at AS updatedAt,completed_at AS completedAt";

    String INTERACTION_COLUMNS = "interaction_id AS interactionId,task_id AS taskId,thread_id AS threadId,cid," +
            "owner_uid AS ownerUid,interaction_type AS interactionType,status,prompt_text AS promptText," +
            "missing_fields_json AS missingFieldsJson,options_json AS optionsJson,answer_json AS answerJson," +
            "answer_client_request_id AS answerClientRequestId,answered_task_id AS answeredTaskId," +
            "created_at AS createdAt,answered_at AS answeredAt,updated_at AS updatedAt";

    @Insert("INSERT INTO agent_task (task_id,turn_id,parent_task_id,thread_id,workspace_id,latest_run_id,cid,owner_uid," +
            "client_request_id,request_fingerprint,status,dialogue_act,relation_type,goal,interpretation_json," +
            "task_spec_json,scope_json,context_json,capability_snapshot_json,version,created_at,updated_at,completed_at) " +
            "VALUES (#{taskId},#{turnId},#{parentTaskId},#{threadId},#{workspaceId},#{latestRunId},#{cid},#{ownerUid}," +
            "#{clientRequestId},#{requestFingerprint},#{status},#{dialogueAct},#{relationType},#{goal}," +
            "#{interpretationJson},#{taskSpecJson},#{scopeJson},#{contextJson},#{capabilitySnapshotJson}," +
            "#{version},#{createdAt},#{updatedAt},#{completedAt})")
    int insertTask(AgentV2Models.Task task);

    @Select("SELECT " + TASK_COLUMNS + " FROM agent_task WHERE task_id=#{taskId} LIMIT 1")
    AgentV2Models.Task selectTask(@Param("taskId") String taskId);

    @Select("SELECT " + TASK_COLUMNS + " FROM agent_task WHERE cid=#{cid} AND owner_uid=#{ownerUid} " +
            "AND client_request_id=#{clientRequestId} LIMIT 1")
    AgentV2Models.Task selectTaskByClientRequest(@Param("cid") String cid,
                                                 @Param("ownerUid") String ownerUid,
                                                 @Param("clientRequestId") String clientRequestId);

    @Select("SELECT " + TASK_COLUMNS + " FROM agent_task WHERE cid=#{cid} AND owner_uid=#{ownerUid} " +
            "AND thread_id=#{threadId} ORDER BY created_at DESC LIMIT 1")
    AgentV2Models.Task selectLatestTask(@Param("cid") String cid,
                                        @Param("ownerUid") String ownerUid,
                                        @Param("threadId") String threadId);

    @Select("SELECT " + TASK_COLUMNS + " FROM agent_task WHERE cid=#{cid} AND owner_uid=#{ownerUid} " +
            "AND thread_id=#{threadId} AND status IN ('OPEN','READY','WAITING_USER') " +
            "ORDER BY updated_at DESC,created_at DESC LIMIT 1")
    AgentV2Models.Task selectLatestActiveTask(@Param("cid") String cid,
                                              @Param("ownerUid") String ownerUid,
                                              @Param("threadId") String threadId);

    @Update("UPDATE agent_task SET updated_at=#{now} WHERE task_id=#{taskId} AND status='OPEN'")
    int claimTaskForInterpretation(@Param("taskId") String taskId, @Param("now") LocalDateTime now);

    @Update("UPDATE agent_task SET status=#{status},parent_task_id=#{parentTaskId},dialogue_act=#{dialogueAct},relation_type=#{relationType}," +
            "goal=#{goal},interpretation_json=#{interpretationJson},task_spec_json=#{taskSpecJson}," +
            "updated_at=#{updatedAt},version=version+1 WHERE task_id=#{taskId} " +
            "AND status IN ('OPEN','READY')")
    int updateInterpretation(@Param("taskId") String taskId,
                             @Param("status") String status,
                             @Param("parentTaskId") String parentTaskId,
                             @Param("dialogueAct") String dialogueAct,
                             @Param("relationType") String relationType,
                             @Param("goal") String goal,
                             @Param("interpretationJson") String interpretationJson,
                             @Param("taskSpecJson") String taskSpecJson,
                             @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE agent_task SET context_json=#{contextJson},updated_at=#{now} WHERE task_id=#{taskId} AND status IN ('OPEN','READY')")
    int updateTaskContext(@Param("taskId") String taskId,
                          @Param("contextJson") String contextJson,
                          @Param("now") LocalDateTime now);

    @Update("UPDATE agent_task SET scope_json=#{scopeJson},capability_snapshot_json=#{capabilitySnapshotJson}," +
            "updated_at=#{now},version=version+1 WHERE task_id=#{taskId} AND status IN ('OPEN','READY')")
    int updateResolvedScope(@Param("taskId") String taskId,
                            @Param("scopeJson") String scopeJson,
                            @Param("capabilitySnapshotJson") String capabilitySnapshotJson,
                            @Param("now") LocalDateTime now);

    @Update("UPDATE agent_task SET status=#{status},updated_at=#{now},completed_at=#{completedAt} " +
            "WHERE task_id=#{taskId} AND status NOT IN ('COMPLETED','BLOCKED','CANCELLED')")
    int updateTaskStatus(@Param("taskId") String taskId,
                         @Param("status") String status,
                         @Param("now") LocalDateTime now,
                         @Param("completedAt") LocalDateTime completedAt);

    @Insert("INSERT INTO agent_interaction (interaction_id,task_id,thread_id,cid,owner_uid,interaction_type,status," +
            "prompt_text,missing_fields_json,options_json,answer_json,answer_client_request_id,answered_task_id," +
            "created_at,answered_at,updated_at) VALUES " +
            "(#{interactionId},#{taskId},#{threadId},#{cid},#{ownerUid},#{interactionType},#{status},#{promptText}," +
            "#{missingFieldsJson},#{optionsJson},#{answerJson},#{answerClientRequestId},#{answeredTaskId}," +
            "#{createdAt},#{answeredAt},#{updatedAt})")
    int insertInteraction(AgentV2Models.Interaction interaction);

    @Select("SELECT " + INTERACTION_COLUMNS + " FROM agent_interaction WHERE interaction_id=#{interactionId} LIMIT 1")
    AgentV2Models.Interaction selectInteraction(@Param("interactionId") String interactionId);

    @Select("SELECT " + INTERACTION_COLUMNS + " FROM agent_interaction WHERE interaction_id=#{interactionId} FOR UPDATE")
    AgentV2Models.Interaction selectInteractionForUpdate(@Param("interactionId") String interactionId);

    @Select("SELECT " + INTERACTION_COLUMNS + " FROM agent_interaction WHERE task_id=#{taskId} " +
            "ORDER BY created_at DESC LIMIT 1")
    AgentV2Models.Interaction selectLatestInteraction(@Param("taskId") String taskId);

    @Update("UPDATE agent_interaction SET status='PROCESSING',answer_json=#{answerJson}," +
            "answer_client_request_id=#{clientRequestId},updated_at=#{now} WHERE interaction_id=#{interactionId} " +
            "AND cid=#{cid} AND owner_uid=#{ownerUid} AND status='PENDING'")
    int claimInteractionAnswer(@Param("interactionId") String interactionId,
                               @Param("cid") String cid,
                               @Param("ownerUid") String ownerUid,
                               @Param("clientRequestId") String clientRequestId,
                               @Param("answerJson") String answerJson,
                               @Param("now") LocalDateTime now);

    @Update("UPDATE agent_interaction SET status='ANSWERED',answered_task_id=#{answeredTaskId}," +
            "answered_at=#{now},updated_at=#{now} WHERE interaction_id=#{interactionId} " +
            "AND cid=#{cid} AND owner_uid=#{ownerUid} AND status='PROCESSING' " +
            "AND answer_client_request_id=#{clientRequestId}")
    int completeInteractionAnswer(@Param("interactionId") String interactionId,
                                  @Param("cid") String cid,
                                  @Param("ownerUid") String ownerUid,
                                  @Param("clientRequestId") String clientRequestId,
                                  @Param("answeredTaskId") String answeredTaskId,
                                  @Param("now") LocalDateTime now);

    @Update("UPDATE agent_interaction SET status='CANCELLED',updated_at=#{now} " +
            "WHERE task_id=#{taskId} AND status IN ('PENDING','PROCESSING')")
    int cancelPendingInteractions(@Param("taskId") String taskId, @Param("now") LocalDateTime now);

    @Update("UPDATE agent_run SET task_id=#{taskId},turn_id=#{turnId},runtime_version='V2' WHERE run_id=#{runId}")
    int linkRun(@Param("runId") String runId,
                @Param("taskId") String taskId,
                @Param("turnId") String turnId);

    @Update("UPDATE agent_message SET task_id=#{taskId},turn_id=#{turnId} WHERE message_id=#{messageId}")
    int linkMessage(@Param("messageId") String messageId,
                    @Param("taskId") String taskId,
                    @Param("turnId") String turnId);

    @Update("UPDATE agent_run SET outcome_json=#{outcomeJson} WHERE run_id=#{runId}")
    int updateRunOutcome(@Param("runId") String runId, @Param("outcomeJson") String outcomeJson);

    @Update("UPDATE agent_run SET status='PLANNING',started_at=#{now},heartbeat_at=#{now},updated_at=#{now} " +
            "WHERE run_id=#{runId} AND task_id=#{taskId} AND runtime_version='V2' AND status='QUEUED'")
    int claimQueuedRun(@Param("runId") String runId,
                       @Param("taskId") String taskId,
                       @Param("now") LocalDateTime now);

    @Update("UPDATE agent_run SET status='CANCELLED',completed_at=#{now},heartbeat_at=#{now},updated_at=#{now}," +
            "error_code=NULL,error_message=NULL WHERE run_id=#{runId} AND task_id=#{taskId} " +
            "AND cid=#{cid} AND owner_uid=#{ownerUid} AND runtime_version='V2' " +
            "AND status IN ('QUEUED','PLANNING','RUNNING')")
    int cancelActiveRun(@Param("runId") String runId,
                        @Param("taskId") String taskId,
                        @Param("cid") String cid,
                        @Param("ownerUid") String ownerUid,
                        @Param("now") LocalDateTime now);

    @Update("UPDATE agent_run SET status='FAILED',completed_at=#{now},heartbeat_at=#{now},updated_at=#{now}," +
            "error_code=#{errorCode},error_message=#{errorMessage} WHERE run_id=#{runId} AND task_id=#{taskId} " +
            "AND cid=#{cid} AND owner_uid=#{ownerUid} AND runtime_version='V2' " +
            "AND status IN ('QUEUED','PLANNING','RUNNING')")
    int failActiveRun(@Param("runId") String runId,
                      @Param("taskId") String taskId,
                      @Param("cid") String cid,
                      @Param("ownerUid") String ownerUid,
                      @Param("errorCode") String errorCode,
                      @Param("errorMessage") String errorMessage,
                      @Param("now") LocalDateTime now);

    @Select("SELECT run_id AS runId,task_id AS taskId,turn_id AS turnId,runtime_version AS runtimeVersion,status," +
            "outcome_json AS outcomeJson,error_code AS errorCode,error_message AS errorMessage," +
            "completed_at AS completedAt FROM agent_run WHERE run_id=#{runId} LIMIT 1")
    AgentV2Models.RunState selectRunState(@Param("runId") String runId);

    @Update("UPDATE agent_task SET status=#{toStatus},updated_at=#{now},completed_at=#{completedAt} " +
            "WHERE task_id=#{taskId} AND status=#{fromStatus}")
    int transitionTaskStatus(@Param("taskId") String taskId,
                             @Param("fromStatus") String fromStatus,
                             @Param("toStatus") String toStatus,
                             @Param("now") LocalDateTime now,
                             @Param("completedAt") LocalDateTime completedAt);
}
