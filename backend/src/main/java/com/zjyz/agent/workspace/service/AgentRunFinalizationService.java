package com.zjyz.agent.workspace.service;

import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeRecords;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class AgentRunFinalizationService {
    private final AgentRuntimeMapper runtimeMapper;
    private final AgentKnowledgeMapper knowledgeMapper;

    public AgentRunFinalizationService(AgentRuntimeMapper runtimeMapper,
                                       AgentKnowledgeMapper knowledgeMapper) {
        this.runtimeMapper = runtimeMapper;
        this.knowledgeMapper = knowledgeMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeRun(AgentRuntimeRecords.Run run,
                               AgentRuntimeRecords.Message assistant,
                               List<AgentRuntimeRecords.Artifact> artifacts,
                               List<AgentKnowledgeRecords.ReferenceAudit> references,
                               LocalDateTime now) {
        if (runtimeMapper.claimRunForCompletion(run.getRunId(), now) <= 0) {
            return false;
        }
        touchOrFail(run.getRunId(), now);
        for (AgentRuntimeRecords.Artifact artifact : safe(artifacts)) {
            runtimeMapper.insertArtifact(artifact);
            touchOrFail(run.getRunId(), LocalDateTime.now());
        }
        runtimeMapper.insertMessage(assistant);
        touchOrFail(run.getRunId(), LocalDateTime.now());
        for (AgentKnowledgeRecords.ReferenceAudit reference : safe(references)) {
            knowledgeMapper.insertReference(reference);
            touchOrFail(run.getRunId(), LocalDateTime.now());
        }
        runtimeMapper.touchThread(run.getThreadId(), now);
        LocalDateTime completedAt = LocalDateTime.now();
        run.setHeartbeatAt(completedAt);
        run.setCompletedAt(completedAt);
        run.setUpdatedAt(completedAt);
        if (runtimeMapper.completeRunIfFinalizing(run) <= 0) {
            throw new IllegalStateException("Agent run lost finalization ownership");
        }
        return true;
    }

    private void touchOrFail(String runId, LocalDateTime now) {
        if (runtimeMapper.touchRunHeartbeat(runId, now) <= 0) {
            throw new IllegalStateException("Agent run is no longer active during finalization");
        }
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
