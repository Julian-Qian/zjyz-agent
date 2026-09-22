package com.zjyz.agent.workspace.service;

import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunWatchdogTest {

    @Test
    void staleRunTransitionsOnceAndPublishesTerminalTimeout() {
        AgentRuntimeMapper mapper = mock(AgentRuntimeMapper.class);
        AgentRunEventService events = mock(AgentRunEventService.class);
        AgentRunWatchdog watchdog = new AgentRunWatchdog(mapper, events);
        ReflectionTestUtils.setField(watchdog, "runTimeoutSeconds", 600L);
        ReflectionTestUtils.setField(watchdog, "batchSize", 20);
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        AgentRuntimeRecords.Run first = run("run-1");
        AgentRuntimeRecords.Run raced = run("run-2");
        when(mapper.selectStaleActiveRuns(now.minusSeconds(600), 20)).thenReturn(Arrays.asList(first, raced));
        when(mapper.timeoutRunIfStale(eq("run-1"), eq(now.minusSeconds(600)), eq(now), any())).thenReturn(1);
        when(mapper.timeoutRunIfStale(eq("run-2"), eq(now.minusSeconds(600)), eq(now), any())).thenReturn(0);

        assertEquals(1, watchdog.terminateStaleRuns(now));

        verify(events).publish(eq("run-1"), eq("run.failed"), any());
        verify(events).complete("run-1");
        verify(events, never()).complete("run-2");
    }

    @Test
    void watchdogRetainsFinalizingCoverageBehindTransactionalFinalizationLock() throws Exception {
        Method select = AgentRuntimeMapper.class.getMethod("selectStaleActiveRuns", LocalDateTime.class, int.class);
        Method timeout = AgentRuntimeMapper.class.getMethod("timeoutRunIfStale",
                String.class, LocalDateTime.class, LocalDateTime.class, String.class);
        String selectSql = String.join(" ", select.getAnnotation(Select.class).value());
        String timeoutSql = String.join(" ", timeout.getAnnotation(Update.class).value());

        assertTrue(selectSql.contains("'FINALIZING'"));
        assertTrue(timeoutSql.contains("'FINALIZING'"));
        assertTrue(timeoutSql.contains("heartbeat_at"));
    }

    private AgentRuntimeRecords.Run run(String runId) {
        AgentRuntimeRecords.Run run = new AgentRuntimeRecords.Run();
        run.setRunId(runId);
        return run;
    }
}
