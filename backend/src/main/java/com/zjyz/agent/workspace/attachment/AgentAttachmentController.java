package com.zjyz.agent.workspace.attachment;

import com.zjyz.common.annotation.ZeeController;
import com.zjyz.membership.web.MembershipEntitlementRequired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@ZeeController
@MembershipEntitlementRequired("AGENT_ENABLED")
@RequestMapping("/agent/v2")
public class AgentAttachmentController {
    private final AgentAttachmentService service;
    public AgentAttachmentController(AgentAttachmentService service){this.service=service;}
    @PostMapping(value="/threads/{threadId}/attachments",consumes="multipart/form-data")
    public Map<String,Object> upload(@PathVariable String threadId,@RequestParam("file") MultipartFile file,@RequestParam String clientRequestId,HttpServletResponse response){Map<String,Object> result=service.upload(threadId,file,clientRequestId);response.setStatus(202);return result;}
    @GetMapping("/attachments/{id}") public Map<String,Object> detail(@PathVariable String id){return service.detail(id);}
    @GetMapping("/attachments/{id}/content") public Map<String,Object> content(@PathVariable String id,@RequestParam int parseRevision,@RequestParam(required=false) String blockId){return service.content(id,parseRevision,blockId);}
    @DeleteMapping("/attachments/{id}") public Map<String,Object> delete(@PathVariable String id){return service.delete(id);}
    @PostMapping("/attachments/{id}/retry") public Map<String,Object> retry(@PathVariable String id,@RequestBody Map<String,Integer> request){Integer version=request.get("expectedVersion");if(version==null)throw new com.zjyz.common.exception.MyBizException("请提供附件版本","AGT400");return service.retry(id,version);}
}
