package com.zjyz.agent.workspace.service;

import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class AgentRunWatchdog {
    static final String TIMEOUT_ERROR_CODE = "AGT_RUN_TIMEOUT";

    private final AgentRuntimeMapper mapper;
    private final AgentRunEventService eventService;

    @Value("${agent.workspace.enabled:${AGENT_WORKSPACE_ENABLED:false}}")
    private boolean workspaceEnabled;
    @Value("${agent.runtime.runTimeoutSeconds:600}")
    private long runTimeoutSeconds;
    @Value("${agent.runtime.watchdog.batchSize:100}")
    private int batchSize;

    public AgentRunWatchdog(AgentRuntimeMapper mapper, AgentRunEventService eventService) {
        this.mapper = mapper;
        this.eventService = eventService;
    }

    @Scheduled(initialDelayString = "${agent.runtime.watchdog.initialDelayMs:60000}",
            fixedDelayString = "${agent.runtime.watchdog.fixedDelayMs:15000}")
    public void terminateStaleRuns() {
        if (!workspaceEnabled) {
            return;
        }
        try {
            terminateStaleRuns(LocalDateTime.now());
        } catch (Exception error) {
            log.warn("Agent run watchdog sweep failed: {}", error.getMessage());
        }
    }

    int terminateStaleRuns(LocalDateTime now) {
        LocalDateTime cutoff = now.minusSeconds(Math.max(runTimeoutSeconds, 60L));
        List<AgentRuntimeRecords.Run> staleRuns = mapper.selectStaleActiveRuns(cutoff, Math.max(batchSize, 1));
        if (staleRuns == null || staleRuns.isEmpty()) {
            return 0;
        }
        String message = "小云执行超过安全时限，任务已终止，请缩小问题范围后重试";
        int terminated = 0;
        for (AgentRuntimeRecords.Run run : staleRuns) {
            if (run == null || mapper.timeoutRunIfStale(run.getRunId(), cutoff, now, message) <= 0) {
                continue;
            }
            terminated++;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "FAILED");
            payload.put("errorCode", TIMEOUT_ERROR_CODE);
            payload.put("message", message);
            eventService.publish(run.getRunId(), "run.failed", payload);
            eventService.complete(run.getRunId());
        }
        if (terminated > 0) {
            log.warn("Terminated {} stale agent runs", terminated);
        }
        return terminated;
    }
}
