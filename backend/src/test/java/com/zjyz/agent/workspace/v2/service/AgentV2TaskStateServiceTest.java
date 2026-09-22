package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentV2TaskStateServiceTest {

    @Test
    void cancelRetriesWhenWorkerAdvancedTaskFromOpenToReady() {
        AgentV2Mapper mapper = mock(AgentV2Mapper.class);
        AgentV2Models.Task open = task("OPEN");
        AgentV2Models.Task ready = task("READY");
        AgentV2Models.Task cancelled = task("CANCELLED");
        AgentV2Models.RunState run = run("PLANNING");
        when(mapper.selectRunState("run-1")).thenReturn(run);
        when(mapper.cancelActiveRun(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(1);
        when(mapper.transitionTaskStatus(eq("task-1"), eq("OPEN"), eq("CANCELLED"), any(), any()))
                .thenReturn(0);
        when(mapper.selectTask("task-1")).thenReturn(ready, cancelled);
        when(mapper.transitionTaskStatus(eq("task-1"), eq("READY"), eq("CANCELLED"), any(), any()))
                .thenReturn(1);
        when(mapper.updateRunOutcome(anyString(), anyString())).thenReturn(1);

        AgentV2Models.Task result = new AgentV2TaskStateService(mapper, new ObjectMapper()).cancel(open);

        assertEquals("CANCELLED", result.getStatus());
        verify(mapper).transitionTaskStatus(eq("task-1"), eq("READY"), eq("CANCELLED"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cancellingWaitingTaskDoesNotRewriteItsCompletedClarificationRunOutcome() {
        AgentV2Mapper mapper = mock(AgentV2Mapper.class);
        AgentV2Models.Task waiting = task("WAITING_USER");
        AgentV2Models.Task cancelled = task("CANCELLED");
        when(mapper.selectRunState("run-1")).thenReturn(run("COMPLETED"));
        when(mapper.transitionTaskStatus(eq("task-1"), eq("WAITING_USER"), eq("CANCELLED"), any(), any()))
                .thenReturn(1);
        when(mapper.selectTask("task-1")).thenReturn(cancelled);

        AgentV2Models.Task result = new AgentV2TaskStateService(mapper, new ObjectMapper()).cancel(waiting);

        assertEquals("CANCELLED", result.getStatus());
        verify(mapper, never()).updateRunOutcome(anyString(), anyString());
    }

    private AgentV2Models.Task task(String status) {
        AgentV2Models.Task task = new AgentV2Models.Task();
        task.setTaskId("task-1");
        task.setLatestRunId("run-1");
        task.setCid("cid-1");
        task.setOwnerUid("uid-1");
        task.setStatus(status);
        return task;
    }

    private AgentV2Models.RunState run(String status) {
        AgentV2Models.RunState run = new AgentV2Models.RunState();
        run.setRunId("run-1");
        run.setStatus(status);
        return run;
    }
}
