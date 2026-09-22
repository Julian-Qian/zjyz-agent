package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/** Atomically commits assistant/artifacts/Task outcome and the Run terminal state. */
@Service
public class AgentV2FinalizationService {
    private final AgentRuntimeMapper runtimeMapper;
    private final AgentV2Mapper v2Mapper;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zjyz.agent.workspace.learning.AgentLearningService learningService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.zjyz.agent.workspace.attachment.AgentAttachmentService attachmentService;

    public AgentV2FinalizationService(AgentRuntimeMapper runtimeMapper, AgentV2Mapper v2Mapper) {
        this.runtimeMapper = runtimeMapper;
        this.v2Mapper = v2Mapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeRun(AgentV2Models.Task task,
                               AgentRuntimeRecords.Run run,
                               AgentRuntimeRecords.Message assistant,
                               List<AgentRuntimeRecords.Artifact> artifacts,
                               String taskStatus,
                               String outcomeJson,
                               LocalDateTime now) {
        if (runtimeMapper.claimRunForCompletion(run.getRunId(), now) <= 0) {
            return false;
        }
        if(learningService!=null&&objectMapper!=null&&assistant.getMetadataJson()!=null) {
            try {
                com.fasterxml.jackson.databind.JsonNode metadata=objectMapper.readTree(assistant.getMetadataJson());
                java.util.List<java.util.Map<String,Object>> refs=metadata.has("learningReferences")?objectMapper.convertValue(metadata.path("learningReferences"),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String,Object>>>(){}):java.util.Collections.emptyList();
                learningService.lockAndValidate(refs);
            } catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Invalid learning references",error);}
        }
        if (assistant.getMetadataJson()!=null) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper parser=objectMapper==null?new com.fasterxml.jackson.databind.ObjectMapper():objectMapper;
                com.fasterxml.jackson.databind.JsonNode review=parser.readTree(assistant.getMetadataJson()).path("documentReview");
                if(review.has("attachmentRefs")) {
                    if(!review.path("attachmentRefs").isArray()||attachmentService==null)
                        throw new IllegalStateException("Attachment validation unavailable");
                    java.util.List<java.util.Map<String,Object>> refs=parser.convertValue(review.path("attachmentRefs"),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<java.util.Map<String,Object>>>(){});
                    attachmentService.lockAndValidate(run.getThreadId(),refs);
                }
            } catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid attachment references",e);}
        }
        touchOrFail(run.getRunId(), now);
        for (AgentRuntimeRecords.Artifact artifact : safe(artifacts)) {
            runtimeMapper.insertArtifact(artifact);
            touchOrFail(run.getRunId(), LocalDateTime.now());
        }
        runtimeMapper.insertMessage(assistant);
        v2Mapper.linkMessage(assistant.getMessageId(), task.getTaskId(), task.getTurnId());
        touchOrFail(run.getRunId(), LocalDateTime.now());
        runtimeMapper.touchThread(run.getThreadId(), now);
        LocalDateTime completedAt = LocalDateTime.now();
        run.setHeartbeatAt(completedAt);
        run.setCompletedAt(completedAt);
        run.setUpdatedAt(completedAt);
        if (v2Mapper.updateRunOutcome(run.getRunId(), outcomeJson) <= 0) {
            throw new IllegalStateException("V2 run outcome lost finalization ownership");
        }
        // persistClarification already commits READY -> WAITING_USER before its recovery event is
        // published. The user may answer immediately and complete that waiting Task while this Run
        // is still finalizing, so finalization must not try to move the Task backwards.
        if (!"WAITING_USER".equals(taskStatus)
                && v2Mapper.updateTaskStatus(task.getTaskId(), taskStatus, completedAt, completedAt) <= 0) {
            throw new IllegalStateException("V2 task lost finalization ownership");
        }
        if (runtimeMapper.completeRunIfFinalizing(run) <= 0) {
            throw new IllegalStateException("V2 run lost finalization ownership");
        }
        return true;
    }

    private void touchOrFail(String runId, LocalDateTime now) {
        if (runtimeMapper.touchRunHeartbeat(runId, now) <= 0) {
            throw new IllegalStateException("V2 run is no longer active during finalization");
        }
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
