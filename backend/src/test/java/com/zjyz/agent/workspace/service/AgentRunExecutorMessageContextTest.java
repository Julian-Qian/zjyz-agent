package com.zjyz.agent.workspace.service;

import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRunExecutorMessageContextTest {

    @Test
    void runHistoryAlwaysEndsAtExactRunMessageAndDropsLaterQueuedMessages() {
        LocalDateTime base = LocalDateTime.of(2026, 8, 26, 10, 0);
        AgentRuntimeRecords.Message earlier = message("m-1", "run-1", "第一个问题", base);
        AgentRuntimeRecords.Message current = message("m-2", "run-2", "当前单项目问题", base.plusSeconds(1));
        AgentRuntimeRecords.Message later = message("m-3", "run-3", "后续多项目问题", base.plusSeconds(2));

        List<AgentRuntimeRecords.Message> history = AgentRunExecutor.historyForRun(
                current, Arrays.asList(later, current, earlier));

        assertEquals(2, history.size());
        assertEquals("第一个问题", history.get(0).getContent());
        assertEquals("当前单项目问题", history.get(1).getContent());
        assertEquals("run-2", history.get(1).getRunId());
    }

    private AgentRuntimeRecords.Message message(String messageId,
                                                String runId,
                                                String content,
                                                LocalDateTime createdAt) {
        AgentRuntimeRecords.Message message = new AgentRuntimeRecords.Message();
        message.setMessageId(messageId);
        message.setRunId(runId);
        message.setRole("user");
        message.setContent(content);
        message.setCreatedAt(createdAt);
        return message;
    }
}
