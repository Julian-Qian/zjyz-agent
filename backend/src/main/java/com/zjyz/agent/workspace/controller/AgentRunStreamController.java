package com.zjyz.agent.workspace.controller;

import com.zjyz.agent.workspace.service.AgentWorkspaceRuntimeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/agent")
public class AgentRunStreamController {
    private final AgentWorkspaceRuntimeService service;

    public AgentRunStreamController(AgentWorkspaceRuntimeService service) {
        this.service = service;
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId,
                             @RequestParam(defaultValue = "0") int afterSeq) {
        return service.subscribe(runId, afterSeq);
    }
}
