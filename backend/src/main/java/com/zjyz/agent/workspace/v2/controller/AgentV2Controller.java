package com.zjyz.agent.workspace.v2.controller;

import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.service.AgentV2CoordinatorService;
import com.zjyz.agent.workspace.v2.service.AgentV2InteractionService;
import com.zjyz.common.annotation.ZeeController;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@ZeeController
@com.zjyz.membership.web.MembershipEntitlementRequired("AGENT_ENABLED")
@RequestMapping("/agent/v2")
@Api(tags = "Agent V2 Rental Operations Copilot")
public class AgentV2Controller {
    private final AgentV2CoordinatorService coordinator;
    private final AgentV2InteractionService interactionService;

    public AgentV2Controller(AgentV2CoordinatorService coordinator,
                             AgentV2InteractionService interactionService) {
        this.coordinator = coordinator;
        this.interactionService = interactionService;
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/capabilities")
    @ApiOperation("查询V2经营副驾能力Manifest")
    public Map<String, Object> capabilities(@RequestParam(required = false) String selectionMode,
                                            @RequestParam(required = false) String scopeMode,
                                            @RequestParam(required = false) List<String> projectIds) {
        String resolvedMode = StringUtils.hasText(selectionMode) ? selectionMode : scopeMode;
        return coordinator.capabilities(resolvedMode,
                projectIds == null ? Collections.emptyList() : projectIds);
    }

    @PostMapping("/threads/{threadId}/turns")
    @ApiOperation("提交V2对话Turn并创建持久Task与短Run")
    public Map<String, Object> createTurn(@PathVariable String threadId,
                                          @RequestBody AgentV2Models.CreateTurnRequest request,
                                          HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        return coordinator.createTurn(threadId, request);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/tasks/{taskId}")
    @ApiOperation("查询V2 Task状态与结构化解释")
    public Map<String, Object> getTask(@PathVariable String taskId) {
        return coordinator.getTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    @ApiOperation("取消V2 Task及其当前短Run")
    public Map<String, Object> cancelTask(@PathVariable String taskId) {
        return coordinator.cancelTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/retry")
    public Map<String,Object> retry(@PathVariable String taskId,@RequestBody Map<String,Object> request,HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        return coordinator.retryTask(taskId,request);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/threads/{threadId}/active-task")
    @ApiOperation("恢复当前会话最新的非终态V2 Task")
    public Map<String, Object> getActiveTask(@PathVariable String threadId) {
        return coordinator.getActiveTask(threadId);
    }

    @PostMapping("/interactions/{interactionId}/answer")
    @ApiOperation("回答V2澄清并创建新的短Run")
    public Map<String, Object> answerInteraction(@PathVariable String interactionId,
                                                 @RequestBody AgentV2Models.AnswerInteractionRequest request,
                                                 HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        return interactionService.answer(interactionId, request);
    }
}
