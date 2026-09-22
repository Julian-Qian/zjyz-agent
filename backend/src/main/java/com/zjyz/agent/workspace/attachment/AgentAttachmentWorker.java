package com.zjyz.agent.workspace.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.common.exception.MyBizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.UUID;
import com.zjyz.agent.workspace.attachment.AgentAttachmentRecords.*;

/** Durable polling, bounded retries and lease fencing. It holds no business/user permissions. */
@Component
public class AgentAttachmentWorker {
    private final AgentAttachmentMapper mapper;private final AgentAttachmentStorage storage;private final AgentAttachmentParser parser;private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.zjyz.agent.workspace.service.AgentModelUsageService usage;
    @Value("${agent.documentIntake.glmOcr.model:${AGENT_DOCUMENT_GLM_OCR_MODEL:glm-ocr}}") private String ocrModel;
    @org.springframework.beans.factory.annotation.Autowired private AgentAttachmentCompletionService completion;
    @Value("${agent.attachment.enabled:false}") private boolean enabled;
    public AgentAttachmentWorker(AgentAttachmentMapper mapper,AgentAttachmentStorage storage,AgentAttachmentParser parser,ObjectMapper json){this.mapper=mapper;this.storage=storage;this.parser=parser;this.json=json;}
    @Scheduled(fixedDelayString="${agent.attachment.workerDelayMs:3000}")
    public void poll(){
        if(!enabled)return;LocalDateTime now=LocalDateTime.now();
        mapper.recover(now.minusMinutes(5),now);mapper.expireJobs(now.minusMinutes(5),now);mapper.reconcileFailures();
        for(Attachment a:mapper.expired(now))completion.expire(a.getAttachmentId());
        for(Attachment a:mapper.cleanup())try{storage.delete(a.getObjectKey());mapper.cleaned(a.getAttachmentId());}catch(Exception ignored){/* next poll retries durable deletion */}
        ParseJob j=mapper.pending();if(j==null)return;
        String lease=UUID.randomUUID().toString();if(mapper.claim(j.getJobId(),lease,now)!=1)return;
        Attachment a=mapper.get(j.getAttachmentId());
        if(a==null||"DELETED".equals(a.getStatus())||a.getExpiresAt().isBefore(now)){mapper.finish(j.getJobId(),lease,"CANCELLED",null,"ATTACHMENT_UNAVAILABLE",now);return;}
        try{
            byte[] bytes=storage.get(a.getObjectKey());
            if(!a.getChecksum().equals(AgentAttachmentService.hash(bytes)))throw new MyBizException("附件校验失败","ATTACHMENT_CHECKSUM_MISMATCH");
            ParsedDocument parsed=parser.parse(a.getFilename(),bytes,()->{
                Attachment current=mapper.get(a.getAttachmentId());
                if(current==null||"DELETED".equals(current.getStatus()))throw new MyBizException("附件不可用","ATTACHMENT_UNAVAILABLE");
                if(usage==null)throw new MyBizException("识别计费不可用","ATTACHMENT_OCR_UNAVAILABLE");
                usage.requireBeforeCall(a.getOwnerUid(),"合同附件OCR");
            },recognized->usage.recordCall(a.getOwnerUid(),"glm",ocrModel,recognized.getPromptTokens(),recognized.getCompletionTokens(),java.math.BigDecimal.ZERO));
            completion.complete(j,lease,parsed.getStatus(),json.writeValueAsString(parsed),null);
        }catch(Exception e){
            String code=e instanceof MyBizException?((MyBizException)e).getErrorCode():"PARSE_FAILED";
            completion.complete(j,lease,"FAILED",null,code);
        }
    }
}
