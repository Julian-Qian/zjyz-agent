package com.zjyz.agent.workspace.controller;

import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.model.AgentWorkspaceRequests;
import com.zjyz.agent.workspace.finance.ReceivableCollectionArtifactExporter;
import com.zjyz.agent.workspace.service.AgentWorkspaceRuntimeService;
import com.zjyz.common.annotation.ZeeController;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@ZeeController
@com.zjyz.membership.web.MembershipEntitlementRequired("AGENT_ENABLED")
@RequestMapping("/agent")
@Api(tags = "Agent Workspace")
public class AgentWorkspaceController {
    private final AgentWorkspaceRuntimeService service;
    private final ReceivableCollectionArtifactExporter artifactExporter;

    public AgentWorkspaceController(AgentWorkspaceRuntimeService service,
                                    ReceivableCollectionArtifactExporter artifactExporter) {
        this.service = service;
        this.artifactExporter = artifactExporter;
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/capabilities")
    @ApiOperation("查询Agent工作台能力")
    public Map<String, Object> capabilities(@RequestParam(required = false) String projectId) {
        return service.capabilities(projectId);
    }

    @PostMapping("/workspaces")
    @ApiOperation("按项目创建或获取工作空间")
    public AgentRuntimeRecords.Workspace createWorkspace(@RequestBody AgentWorkspaceRequests.CreateWorkspace request) {
        return service.createWorkspace(request);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/workspaces/{workspaceId}")
    public AgentRuntimeRecords.Workspace getWorkspace(@PathVariable String workspaceId) {
        return service.getWorkspace(workspaceId);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/workspaces/{workspaceId}/threads")
    public List<AgentRuntimeRecords.AgentThread> listThreads(@PathVariable String workspaceId) {
        return service.listThreads(workspaceId);
    }

    @PostMapping("/workspaces/{workspaceId}/threads")
    public AgentRuntimeRecords.AgentThread createThread(@PathVariable String workspaceId,
                                                        @RequestBody(required = false) AgentWorkspaceRequests.CreateThread request) {
        return service.createThread(workspaceId, request);
    }

    @PatchMapping("/threads/{threadId}")
    public AgentRuntimeRecords.AgentThread updateThread(@PathVariable String threadId,
                                                        @RequestBody AgentWorkspaceRequests.UpdateThread request) {
        return service.updateThread(threadId, request);
    }

    @DeleteMapping("/threads/{threadId}")
    public Map<String, Object> archiveThread(@PathVariable String threadId) {
        return service.archiveThread(threadId);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/threads/{threadId}/messages")
    public List<Map<String, Object>> listMessages(@PathVariable String threadId) {
        return service.listMessages(threadId);
    }

    @PostMapping("/threads/{threadId}/runs")
    public Map<String, Object> createRun(@PathVariable String threadId,
                                         @RequestBody AgentWorkspaceRequests.CreateRun request,
                                         HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        return service.createRun(threadId, request);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRun(@PathVariable String runId) {
        return service.getRun(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Map<String, Object> cancelRun(@PathVariable String runId) {
        return service.cancelRun(runId);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/threads/{threadId}/artifacts")
    public List<Map<String, Object>> listArtifacts(@PathVariable String threadId) {
        return service.listArtifacts(threadId);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/artifacts/{artifactId}")
    public Map<String, Object> getArtifact(@PathVariable String artifactId) {
        return service.getArtifact(artifactId);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/artifacts/{artifactId}/download")
    @ApiOperation("下载Agent结构化产物Excel")
    public void downloadArtifact(@PathVariable String artifactId, HttpServletResponse response) throws java.io.IOException {
        AgentRuntimeRecords.Artifact artifact = service.requireArtifactAccess(artifactId);
        byte[] bytes = artifactExporter.export(artifact);
        String filename = URLEncoder.encode(artifact.getTitle() + ".xlsx", StandardCharsets.UTF_8.name())
                .replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        response.flushBuffer();
    }
}
