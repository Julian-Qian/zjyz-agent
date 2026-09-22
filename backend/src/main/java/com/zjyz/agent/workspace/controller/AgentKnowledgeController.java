package com.zjyz.agent.workspace.controller;

import com.zjyz.agent.workspace.knowledge.AgentKnowledgeManagementService;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeRecords;
import com.zjyz.common.annotation.ZeeController;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@ZeeController
@com.zjyz.membership.web.MembershipEntitlementRequired("AGENT_ENABLED")
@RequestMapping("/agent/knowledge")
@Api(tags = "Agent AI知识库")
public class AgentKnowledgeController {
    private final AgentKnowledgeManagementService service;

    public AgentKnowledgeController(AgentKnowledgeManagementService service) {
        this.service = service;
    }

    @PostMapping(value = "/sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiOperation("上传企业知识文档")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam("title") String title,
                                      @RequestParam(required = false) String description,
                                      @RequestParam(required = false) String domain,
                                      @RequestParam(required = false) String tags,
                                      @RequestParam(required = false) String clientRequestId,
                                      HttpServletResponse response) {
        Map<String, Object> result = service.upload(file, title, description, domain, tags, clientRequestId);
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        return result;
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/sources")
    @ApiOperation("分页查询企业知识文档")
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int pageNum,
                                    @RequestParam(defaultValue = "20") int pageSize,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String domain,
                                    @RequestParam(required = false) String lifecycleStatus,
                                    @RequestParam(required = false) String indexStatus) {
        return service.list(pageNum, pageSize, keyword, domain, lifecycleStatus, indexStatus);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/sources/{sourceId}")
    public Map<String, Object> detail(@PathVariable String sourceId) {
        return service.detail(sourceId);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/sources/{sourceId}/chunks")
    public Map<String, Object> chunks(@PathVariable String sourceId,
                                      @RequestParam(defaultValue = "1") int pageNum,
                                      @RequestParam(defaultValue = "20") int pageSize) {
        return service.chunks(sourceId, pageNum, pageSize);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/sources/{sourceId}/download")
    public void download(@PathVariable String sourceId, HttpServletResponse response) throws java.io.IOException {
        AgentKnowledgeManagementService.Download download = service.download(sourceId);
        String encoded = URLEncoder.encode(download.getFilename(), StandardCharsets.UTF_8.name()).replace("+", "%20");
        response.setContentType(download.getMimeType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : download.getMimeType());
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        response.setContentLength(download.getBytes().length);
        response.getOutputStream().write(download.getBytes());
        response.flushBuffer();
    }

    @PostMapping("/sources/{sourceId}/publish")
    public Map<String, Object> publish(@PathVariable String sourceId,
                                       @RequestBody(required = false) Map<String, Object> request) {
        Integer expectedVersion = request == null || request.get("expectedVersion") == null
                ? null : Integer.valueOf(String.valueOf(request.get("expectedVersion")));
        return service.publish(sourceId, expectedVersion);
    }

    @PostMapping("/sources/{sourceId}/unpublish")
    public Map<String, Object> unpublish(@PathVariable String sourceId) {
        return service.unpublish(sourceId);
    }

    @PostMapping("/sources/{sourceId}/reindex")
    public Map<String, Object> reindex(@PathVariable String sourceId) {
        return service.reindex(sourceId);
    }

    @DeleteMapping("/sources/{sourceId}")
    public Map<String, Object> archive(@PathVariable String sourceId) {
        return service.archive(sourceId);
    }

    @PostMapping("/search-preview")
    public AgentKnowledgeRecords.SearchPreview preview(@RequestBody Map<String, Object> request) {
        Object queryValue = request == null ? null : request.get("query");
        String query = queryValue == null ? null : String.valueOf(queryValue);
        Integer limit = request == null || request.get("limit") == null
                ? null : Integer.valueOf(String.valueOf(request.get("limit")));
        return service.preview(query, limit);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/jobs/{jobId}")
    public Map<String, Object> job(@PathVariable String jobId) {
        return service.job(jobId);
    }

    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/management-capability")
    public Map<String, Object> capability() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("managementEnabled", service.manageAllowed());
        result.put("vectorConfigured", service.vectorConfigured());
        return result;
    }
}
