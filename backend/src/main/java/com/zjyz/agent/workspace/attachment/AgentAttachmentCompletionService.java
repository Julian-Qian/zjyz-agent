package com.zjyz.agent.workspace.attachment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import com.zjyz.agent.workspace.attachment.AgentAttachmentRecords.*;

/** Short transaction only; parsing and external OCR are always outside it. */
@Service
public class AgentAttachmentCompletionService {
    private final AgentAttachmentMapper mapper;
    public AgentAttachmentCompletionService(AgentAttachmentMapper mapper){this.mapper=mapper;}

    @Transactional(rollbackFor=Exception.class)
    public void complete(ParseJob job,String lease,String status,String result,String error){
        Attachment attachment=mapper.lock(job.getAttachmentId());
        if(attachment==null||"DELETED".equals(attachment.getStatus())||!attachment.getExpiresAt().isAfter(LocalDateTime.now())){
            mapper.finish(job.getJobId(),lease,"CANCELLED",null,"ATTACHMENT_UNAVAILABLE",LocalDateTime.now());return;
        }
        if(mapper.finish(job.getJobId(),lease,status,result,error,LocalDateTime.now())==1
            && mapper.parsed(job.getAttachmentId(),job.getRevision(),status)!=1)
            throw new IllegalStateException("Attachment revision changed during completion");
    }

    @Transactional(rollbackFor=Exception.class)
    public void expire(String id){
        Attachment attachment=mapper.lock(id);
        if(attachment!=null&&!attachment.getExpiresAt().isAfter(LocalDateTime.now())){
            mapper.delete(id);mapper.cancelJobs(id);
        }
    }
}
