package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.common.security.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentV2InteractionServiceTest {

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void answeredInteractionCreatesChildAndCompletesWaitingParent() throws Exception {
        AgentV2Mapper mapper = mock(AgentV2Mapper.class);
        AgentV2CoordinatorService coordinator = mock(AgentV2CoordinatorService.class);
        AgentV2Models.Interaction interaction = new AgentV2Models.Interaction();
        interaction.setInteractionId("interaction-1");
        interaction.setTaskId("parent-1");
        interaction.setThreadId("thread-1");
        interaction.setCid("cid-1");
        interaction.setOwnerUid("uid-1");
        interaction.setStatus("PENDING");
        interaction.setPromptText("请补充月份");
        AgentV2Models.Task parent = new AgentV2Models.Task();
        parent.setTaskId("parent-1");
        parent.setThreadId("thread-1");
        AgentV2Models.ScopeSnapshot scope = new AgentV2Models.ScopeSnapshot();
        scope.setProjectIds(Collections.singletonList("p1"));
        parent.setScopeJson(new ObjectMapper().writeValueAsString(scope));
        when(mapper.selectInteractionForUpdate("interaction-1")).thenReturn(interaction);
        when(mapper.claimInteractionAnswer(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(mapper.completeInteractionAnswer(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(mapper.transitionTaskStatus(anyString(), anyString(), anyString(), any(), any())).thenReturn(1);
        when(coordinator.requireTaskAccess("parent-1")).thenReturn(parent);
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("taskId", "child-1");
        when(coordinator.createTurnFromInteraction(anyString(), any(), anyString())).thenReturn(child);
        AuthContext.set("uid-1", "cid-1");
        AgentV2InteractionService service = new AgentV2InteractionService(mapper, coordinator, new ObjectMapper());
        AgentV2Models.AnswerInteractionRequest request = new AgentV2Models.AnswerInteractionRequest();
        request.setAnswer(Collections.singletonMap("value", "上个月"));
        request.setClientRequestId("answer-request-1");

        Map<String, Object> result = service.answer("interaction-1", request);

        assertEquals("child-1", result.get("taskId"));
        verify(mapper).transitionTaskStatus(org.mockito.ArgumentMatchers.eq("parent-1"),
                org.mockito.ArgumentMatchers.eq("WAITING_USER"),
                org.mockito.ArgumentMatchers.eq("COMPLETED"), any(), any());
        verify(mapper).completeInteractionAnswer(org.mockito.ArgumentMatchers.eq("interaction-1"),
                org.mockito.ArgumentMatchers.eq("cid-1"), org.mockito.ArgumentMatchers.eq("uid-1"),
                org.mockito.ArgumentMatchers.eq("answer-request-1"),
                org.mockito.ArgumentMatchers.eq("child-1"), any());
        ArgumentCaptor<AgentV2Models.CreateTurnRequest> turn = ArgumentCaptor.forClass(
                AgentV2Models.CreateTurnRequest.class);
        verify(coordinator).createTurnFromInteraction(org.mockito.ArgumentMatchers.eq("thread-1"),
                turn.capture(), org.mockito.ArgumentMatchers.eq("parent-1"));
        assertEquals("interaction-1", turn.getValue().getContext().get("interactionId"));
        assertEquals(Boolean.FALSE, turn.getValue().getScopeSelection().getExplicitOverride());
    }

    @Test
    void identicalAnswerRetryReturnsExistingChildWithoutCreatingAnotherTask() {
        AgentV2Mapper mapper = mock(AgentV2Mapper.class);
        AgentV2CoordinatorService coordinator = mock(AgentV2CoordinatorService.class);
        AgentV2Models.Interaction interaction = new AgentV2Models.Interaction();
        interaction.setInteractionId("interaction-1");
        interaction.setTaskId("parent-1");
        interaction.setCid("cid-1");
        interaction.setOwnerUid("uid-1");
        interaction.setStatus("ANSWERED");
        interaction.setAnswerClientRequestId("answer-request-1");
        interaction.setAnsweredTaskId("child-1");
        when(mapper.selectInteractionForUpdate("interaction-1")).thenReturn(interaction);
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("taskId", "child-1");
        existing.put("taskStatus", "OPEN");
        when(coordinator.getTask("child-1")).thenReturn(existing);
        AuthContext.set("uid-1", "cid-1");
        AgentV2Models.AnswerInteractionRequest request = new AgentV2Models.AnswerInteractionRequest();
        request.setClientRequestId("answer-request-1");
        request.setMessage("上个月");

        Map<String, Object> result = new AgentV2InteractionService(mapper, coordinator, new ObjectMapper())
                .answer("interaction-1", request);

        assertEquals("child-1", result.get("taskId"));
        verify(coordinator, never()).createTurnFromInteraction(anyString(), any(), anyString());
        verify(mapper, never()).claimInteractionAnswer(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }
}
