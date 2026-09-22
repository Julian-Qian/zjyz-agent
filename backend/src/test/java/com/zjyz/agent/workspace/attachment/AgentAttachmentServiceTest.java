package com.zjyz.agent.workspace.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.security.AuthContext;
import com.zjyz.common.exception.MyBizException;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import com.zjyz.agent.workspace.attachment.AgentAttachmentRecords.*;

class AgentAttachmentServiceTest {
    AgentAttachmentMapper mapper=mock(AgentAttachmentMapper.class);AgentRuntimeMapper runtime=mock(AgentRuntimeMapper.class);
    AgentAttachmentService service=new AgentAttachmentService(mapper,runtime,mock(AgentAttachmentStorage.class),new AgentAttachmentParser(),new ObjectMapper());
    @BeforeEach void setup(){AuthContext.set("u","c");ReflectionTestUtils.setField(service,"enabled",true);AgentRuntimeRecords.AgentThread thread=new AgentRuntimeRecords.AgentThread();thread.setOwnerUid("u");thread.setStatus("ACTIVE");thread.setWorkspaceId("w");when(runtime.selectThread("c","t")).thenReturn(thread);AgentRuntimeRecords.Workspace w=new AgentRuntimeRecords.Workspace();w.setStatus("ACTIVE");when(runtime.selectWorkspaceById("c","w")).thenReturn(w);}
    @AfterEach void cleanup(){AuthContext.clear();}
    @Test void rejectsCrossTenantAttachment(){Attachment a=attachment();a.setCid("another");when(mapper.get("a")).thenReturn(a);assertThrows(MyBizException.class,()->service.freeze("t",List.of("a")));}
    @Test void frozenReferenceRetainsEarlierParseRevision()throws Exception{
        Attachment a=attachment();when(mapper.get("a")).thenReturn(a);ParseJob j=new ParseJob();j.setStatus("READY");j.setResultJson(new ObjectMapper().writeValueAsString(new ParsedDocument()));when(mapper.revision("a",1)).thenReturn(j);
        List<Map<String,Object>> refs=service.freeze("t",List.of("a"));a.setParseRevision(2);a.setStatus("PENDING");assertNotNull(service.readFrozen("t",refs.get(0)));verify(mapper,atLeastOnce()).revision("a",1);
    }
    @Test void deletionInvalidatesFrozenReference()throws Exception{
        Attachment a=attachment();when(mapper.get("a")).thenReturn(a);a.setStatus("DELETED");assertThrows(MyBizException.class,()->service.readFrozen("t",Map.of("attachmentId","a","checksum","hash","version",1,"parseRevision",1)));}
    @Test void pendingParseIsNeverSilentlyIgnored(){Attachment a=attachment();when(mapper.get("a")).thenReturn(a);assertThrows(MyBizException.class,()->service.freeze("t",List.of("a")));}
    @Test void publicationLockRejectsDeletedAttachmentWithoutReadingOldParse(){
        Attachment a=attachment();a.setStatus("DELETED");when(mapper.lock("a")).thenReturn(a);
        assertThrows(MyBizException.class,()->service.lockAndValidate("t",List.of(Map.of("attachmentId","a","checksum","hash","version",1,"parseRevision",1))));
        verify(mapper,never()).revision(anyString(),anyInt());
    }
    @Test void deleteLocksSameAttachmentBeforeRevokingParsedContent(){
        when(mapper.lock("a")).thenReturn(attachment());service.delete("a");
        org.mockito.InOrder order=inOrder(mapper);order.verify(mapper).lock("a");order.verify(mapper).delete("a");order.verify(mapper).cancelJobs("a");
    }
    private Attachment attachment(){Attachment a=new Attachment();a.setAttachmentId("a");a.setCid("c");a.setOwnerUid("u");a.setThreadId("t");a.setChecksum("hash");a.setVersion(1);a.setParseRevision(1);a.setStatus("READY");a.setExpiresAt(LocalDateTime.now().plusDays(1));return a;}
}
