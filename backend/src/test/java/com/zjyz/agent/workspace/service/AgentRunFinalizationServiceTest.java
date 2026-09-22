package com.zjyz.agent.workspace.service;

import com.zjyz.agent.dao.AgentKnowledgeMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeRecords;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunFinalizationServiceTest {

    @Test
    void watchdogWinningBeforeClaimProducesNoAnswerOrArtifact() {
        AgentRuntimeMapper runtimeMapper = mock(AgentRuntimeMapper.class);
        AgentKnowledgeMapper knowledgeMapper = mock(AgentKnowledgeMapper.class);
        when(runtimeMapper.claimRunForCompletion(any(), any())).thenReturn(0);
        AgentRunFinalizationService service = new AgentRunFinalizationService(runtimeMapper, knowledgeMapper);

        assertFalse(service.finalizeRun(run(), message(), Collections.singletonList(artifact()),
                Collections.singletonList(reference()), LocalDateTime.now()));

        verify(runtimeMapper, never()).insertArtifact(any());
        verify(runtimeMapper, never()).insertMessage(any());
        verify(knowledgeMapper, never()).insertReference(any());
        verify(runtimeMapper, never()).completeRunIfFinalizing(any());
    }

    @Test
    void claimedFinalizationPersistsSideEffectsBeforeConditionalCompletionInOneTransaction() throws Exception {
        AgentRuntimeMapper runtimeMapper = mock(AgentRuntimeMapper.class);
        AgentKnowledgeMapper knowledgeMapper = mock(AgentKnowledgeMapper.class);
        when(runtimeMapper.claimRunForCompletion(any(), any())).thenReturn(1);
        when(runtimeMapper.touchRunHeartbeat(any(), any())).thenReturn(1);
        when(runtimeMapper.completeRunIfFinalizing(any())).thenReturn(1);
        AgentRunFinalizationService service = new AgentRunFinalizationService(runtimeMapper, knowledgeMapper);
        AgentRuntimeRecords.Run run = run();
        AgentRuntimeRecords.Artifact artifact = artifact();
        AgentRuntimeRecords.Message message = message();
        AgentKnowledgeRecords.ReferenceAudit reference = reference();

        assertTrue(service.finalizeRun(run, message, Collections.singletonList(artifact),
                Collections.singletonList(reference), LocalDateTime.now()));

        org.mockito.InOrder order = inOrder(runtimeMapper, knowledgeMapper);
        order.verify(runtimeMapper).claimRunForCompletion(eq("run-1"), any());
        order.verify(runtimeMapper).touchRunHeartbeat(eq("run-1"), any());
        order.verify(runtimeMapper).insertArtifact(artifact);
        order.verify(runtimeMapper).touchRunHeartbeat(eq("run-1"), any());
        order.verify(runtimeMapper).insertMessage(message);
        order.verify(runtimeMapper).touchRunHeartbeat(eq("run-1"), any());
        order.verify(knowledgeMapper).insertReference(reference);
        order.verify(runtimeMapper).touchRunHeartbeat(eq("run-1"), any());
        order.verify(runtimeMapper).touchThread(eq("thread-1"), any());
        order.verify(runtimeMapper).completeRunIfFinalizing(run);

        Method method = AgentRunFinalizationService.class.getMethod("finalizeRun",
                AgentRuntimeRecords.Run.class, AgentRuntimeRecords.Message.class, java.util.List.class,
                java.util.List.class, LocalDateTime.class);
        assertTrue(method.isAnnotationPresent(Transactional.class));
    }

    @Test
    void lostCompletionCasThrowsSoTransactionalProxyRollsBackAllSideEffects() {
        AgentRuntimeMapper runtimeMapper = mock(AgentRuntimeMapper.class);
        when(runtimeMapper.claimRunForCompletion(any(), any())).thenReturn(1);
        when(runtimeMapper.touchRunHeartbeat(any(), any())).thenReturn(1);
        when(runtimeMapper.completeRunIfFinalizing(any())).thenReturn(0);
        AgentRunFinalizationService service = new AgentRunFinalizationService(
                runtimeMapper, mock(AgentKnowledgeMapper.class));

        assertThrows(IllegalStateException.class, () -> service.finalizeRun(
                run(), message(), Collections.emptyList(), Collections.emptyList(), LocalDateTime.now()));
    }

    private AgentRuntimeRecords.Run run() {
        AgentRuntimeRecords.Run run = new AgentRuntimeRecords.Run();
        run.setRunId("run-1");
        run.setThreadId("thread-1");
        return run;
    }

    private AgentRuntimeRecords.Message message() {
        return new AgentRuntimeRecords.Message();
    }

    private AgentRuntimeRecords.Artifact artifact() {
        return new AgentRuntimeRecords.Artifact();
    }

    private AgentKnowledgeRecords.ReferenceAudit reference() {
        return new AgentKnowledgeRecords.ReferenceAudit();
    }
}
