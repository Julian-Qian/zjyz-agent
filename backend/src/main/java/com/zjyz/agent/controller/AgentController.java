package com.zjyz.agent.controller;

import com.zjyz.agent.model.AgentChatRequest;
import com.zjyz.agent.model.AgentChatResponse;
import com.zjyz.agent.service.AgentChatService;
import com.zjyz.common.annotation.ZeeController;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@ZeeController
@com.zjyz.membership.web.MembershipEntitlementRequired("AGENT_ENABLED")
@RequestMapping("/agent")
@Api(tags = "Agent-Beta")
public class AgentController {

    @Autowired
    private AgentChatService agentChatService;

    @PostMapping("/chat")
    @ApiOperation("Agent 对话入口")
    public AgentChatResponse chat(@RequestBody AgentChatRequest request) {
        return agentChatService.chat(request);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/health")
    @ApiOperation("Agent 健康检查")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("time", LocalDateTime.now().toString());
        return result;
    }
}
