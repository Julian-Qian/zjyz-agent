package com.zjyz.agent.workspace.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.orch.AgentIntentClassifier;
import com.zjyz.agent.workspace.context.AgentAnswerGuard;
import com.zjyz.agent.workspace.context.AgentTaskFrame;
import com.zjyz.agent.workspace.context.AgentTaskFrameBuilder;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeContext;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeRetriever;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.tool.AgentRuntimeToolRegistry;
import com.zjyz.dao.FeatureAccountWhitelistMapper;
import com.zjyz.dao.ProjectMapper;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunCompletionCasTest {

    @Test
    void losingCompletionClaimProducesNoAnswerArtifactOrCompletedEvent() {
        AgentRuntimeMapper mapper = mock(AgentRuntimeMapper.class);
        AgentRunEventService events = mock(AgentRunEventService.class);
        AgentRuntimeRecords.Run run = new AgentRuntimeRecords.Run();
        run.setRunId("run-1");
        run.setStatus("RUNNING");
        when(mapper.selectRun("run-1")).thenReturn(run);
        AgentRunFinalizationService finalizationService = mock(AgentRunFinalizationService.class);
        when(finalizationService.finalizeRun(any(), any(), any(), any(), any())).thenReturn(false);
        AgentRunExecutor executor = executor(mapper, events, finalizationService);

        ReflectionTestUtils.invokeMethod(executor, "complete",
                run,
                new AgentRuntimeRecords.Workspace(),
                "不应落库的回答",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                "provider",
                "model",
                false,
                1,
                0,
                0,
                0,
                new AgentTaskFrame(),
                new AgentKnowledgeContext());

        verify(mapper, never()).insertMessage(any());
        verify(mapper, never()).insertArtifact(any());
        verify(events, never()).publish(any(), any(), any());
    }

    @Test
    void genericRunUpdatesCannotOverwriteTerminalState() throws Exception {
        Method method = AgentRuntimeMapper.class.getMethod("updateRun", AgentRuntimeRecords.Run.class);
        String sql = String.join(" ", method.getAnnotation(Update.class).value());
        assertTrue(sql.contains("status NOT IN ('COMPLETED','FAILED','CANCELLED','INTERRUPTED')"));
    }

    @Test
    void heartbeatTouchStopsWorkAfterRunBecomesTerminal() {
        AgentRuntimeMapper mapper = mock(AgentRuntimeMapper.class);
        when(mapper.touchRunHeartbeat(eq("run-active"), any())).thenReturn(1);
        when(mapper.touchRunHeartbeat(eq("run-terminal"), any())).thenReturn(0);
        AgentRunExecutor executor = executor(mapper, mock(AgentRunEventService.class),
                mock(AgentRunFinalizationService.class));
        AgentRuntimeRecords.Run active = new AgentRuntimeRecords.Run();
        active.setRunId("run-active");
        AgentRuntimeRecords.Run terminal = new AgentRuntimeRecords.Run();
        terminal.setRunId("run-terminal");

        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(executor, "touchActiveRun", active)));
        assertTrue(active.getHeartbeatAt() != null);
        assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(executor, "touchActiveRun", terminal)));
    }

    private AgentRunExecutor executor(AgentRuntimeMapper mapper,
                                      AgentRunEventService events,
                                      AgentRunFinalizationService finalizationService) {
        return new AgentRunExecutor(
                mapper,
                events,
                mock(AgentModelGateway.class),
                mock(AgentRuntimeToolRegistry.class),
                new AgentAnswerGuard(),
                mock(AgentTaskFrameBuilder.class),
                mock(AgentKnowledgeRetriever.class),
                mock(AgentIntentClassifier.class),
                mock(AgentModelUsageService.class),
                new ObjectMapper(),
                mock(ProjectMapper.class),
                mock(FeatureAccountWhitelistMapper.class),
                finalizationService);
    }
}
