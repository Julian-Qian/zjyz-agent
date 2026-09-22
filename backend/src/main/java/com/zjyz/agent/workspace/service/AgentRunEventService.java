package com.zjyz.agent.workspace.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.util.CommonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class AgentRunEventService {
    private final AgentRuntimeMapper mapper;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, AtomicInteger> sequences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    @Value("${agent.runtime.sse.timeoutMs:1800000}")
    private long emitterTimeoutMs;

    public AgentRunEventService(AgentRuntimeMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public AgentRuntimeRecords.RunEvent publish(String runId, String eventType, Object payload) {
        AtomicInteger counter = sequences.computeIfAbsent(runId,
                key -> new AtomicInteger(mapper.selectMaxEventSeq(key)));
        AgentRuntimeRecords.RunEvent event = new AgentRuntimeRecords.RunEvent();
        event.setEventId("aev_" + CommonUtil.createUuid());
        event.setRunId(runId);
        event.setSeqNo(counter.incrementAndGet());
        event.setEventType(eventType);
        event.setPayloadJson(toJson(payload));
        event.setVisibleToUser(1);
        event.setCreatedAt(LocalDateTime.now());
        mapper.insertRunEvent(event);
        broadcast(event);
        return event;
    }

    public SseEmitter subscribe(String runId, int afterSeq, boolean terminal) {
        SseEmitter emitter = new SseEmitter(Math.max(emitterTimeoutMs, 60000L));
        CopyOnWriteArrayList<SseEmitter> list = subscribers.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> {
            remove(runId, emitter);
            emitter.complete();
        });
        emitter.onError(error -> remove(runId, emitter));

        try {
            List<AgentRuntimeRecords.RunEvent> events = mapper.selectRunEvents(runId, Math.max(afterSeq, 0), 500);
            for (AgentRuntimeRecords.RunEvent event : events) {
                send(emitter, event);
            }
            if (terminal) {
                remove(runId, emitter);
                emitter.complete();
            }
        } catch (Exception e) {
            remove(runId, emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * SSE 层心跳：只推送给在线订阅者，不落库、不占用 Run 事件序号，
     * 用于保持连接活性并及时清理已断开的 emitter。
     */
    @Scheduled(fixedDelayString = "${agent.runtime.sse.heartbeatMs:15000}")
    public void heartbeat() {
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : subscribers.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data("{}", MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    remove(entry.getKey(), emitter);
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                        // emitter already closed
                    }
                }
            }
        }
    }

    public void complete(String runId) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.remove(runId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // emitter already closed
            }
        }
    }

    private void broadcast(AgentRuntimeRecords.RunEvent event) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(event.getRunId());
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, event);
            } catch (Exception e) {
                remove(event.getRunId(), emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // already closed
                }
            }
        }
    }

    private void send(SseEmitter emitter, AgentRuntimeRecords.RunEvent event) throws Exception {
        Object payload = event.getPayloadJson();
        try {
            payload = objectMapper.readValue(event.getPayloadJson(), Object.class);
        } catch (Exception ignored) {
            // keep safe string payload
        }
        emitter.send(SseEmitter.event()
                .id(String.valueOf(event.getSeqNo()))
                .name(event.getEventType())
                .data(payload, MediaType.APPLICATION_JSON));
    }

    private void remove(String runId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = subscribers.get(runId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            subscribers.remove(runId, emitters);
        }
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? java.util.Collections.emptyMap() : payload);
        } catch (Exception e) {
            log.warn("serialize agent event failed: {}", e.getMessage());
            return "{}";
        }
    }
}
