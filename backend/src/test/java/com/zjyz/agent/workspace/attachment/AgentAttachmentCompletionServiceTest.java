package com.zjyz.agent.workspace.attachment;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import com.zjyz.agent.workspace.attachment.AgentAttachmentRecords.*;

class AgentAttachmentCompletionServiceTest {
    @Test void lostWorkerLeaseCannotPublishAttachmentState(){
        AgentAttachmentMapper mapper=mock(AgentAttachmentMapper.class);when(mapper.lock("a")).thenReturn(attachment());
        new AgentAttachmentCompletionService(mapper).complete(job(),"old-lease","READY","{}",null);
        verify(mapper,never()).parsed(anyString(),anyInt(),anyString());
    }
    @Test void attachmentTransitionFailureAbortsCompletionTransaction(){
        AgentAttachmentMapper mapper=mock(AgentAttachmentMapper.class);when(mapper.lock("a")).thenReturn(attachment());
        when(mapper.finish(eq("j"),eq("lease"),eq("READY"),eq("{}"),isNull(),any())).thenReturn(1);
        assertThrows(IllegalStateException.class,()->new AgentAttachmentCompletionService(mapper).complete(job(),"lease","READY","{}",null));
    }
    @Test void deletionWinsBeforeLateParseResult(){
        AgentAttachmentMapper mapper=mock(AgentAttachmentMapper.class);Attachment a=attachment();a.setStatus("DELETED");when(mapper.lock("a")).thenReturn(a);
        new AgentAttachmentCompletionService(mapper).complete(job(),"lease","READY","private parsed text",null);
        verify(mapper).finish(eq("j"),eq("lease"),eq("CANCELLED"),isNull(),eq("ATTACHMENT_UNAVAILABLE"),any());
        verify(mapper,never()).parsed(anyString(),anyInt(),anyString());
    }
    private Attachment attachment(){Attachment a=new Attachment();a.setStatus("PENDING");a.setExpiresAt(LocalDateTime.now().plusDays(1));return a;}
    private ParseJob job(){ParseJob j=new ParseJob();j.setAttachmentId("a");j.setJobId("j");j.setRevision(1);return j;}
}
