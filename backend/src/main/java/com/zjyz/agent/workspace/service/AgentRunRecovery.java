package com.zjyz.agent.workspace.service;

import com.zjyz.agent.dao.AgentRuntimeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@Slf4j
public class AgentRunRecovery {
    private final AgentRuntimeMapper mapper;

    public AgentRunRecovery(AgentRuntimeMapper mapper) {
        this.mapper = mapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void interruptStaleRuns() {
        try {
            int count = mapper.interruptActiveRuns(LocalDateTime.now());
            if (count > 0) {
                log.warn("Marked {} stale agent runs as INTERRUPTED after startup", count);
            }
        } catch (Exception e) {
            log.warn("Agent runtime recovery skipped; apply the runtime migration before enabling the workspace: {}", e.getMessage());
        }
    }
}
