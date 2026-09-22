package com.zjyz.agent.workspace.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunEventServiceHeartbeatTest {

    @Test
    void heartbeatKeepsLiveEmitterAndRemovesClosedEmitter() {
        AgentRuntimeMapper mapper = mock(AgentRuntimeMapper.class);
        when(mapper.selectRunEvents(anyString(), anyInt(), anyInt())).thenReturn(Collections.emptyList());
        AgentRunEventService service = new AgentRunEventService(mapper, new ObjectMapper());
        ReflectionTestUtils.setField(service, "emitterTimeoutMs", 60000L);

        SseEmitter live = service.subscribe("run-live", 0, false);
        SseEmitter closed = service.subscribe("run-closed", 0, false);
        closed.complete();

        service.heartbeat();

        ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = subscribers(service);
        assertTrue(subscribers.containsKey("run-live"), "在线订阅者应保留");
        assertTrue(subscribers.get("run-live").contains(live));
        assertFalse(subscribers.containsKey("run-closed"), "已关闭的 emitter 应在心跳时被清理");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers(AgentRunEventService service) {
        return (ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>>)
                ReflectionTestUtils.getField(service, "subscribers");
    }
}
