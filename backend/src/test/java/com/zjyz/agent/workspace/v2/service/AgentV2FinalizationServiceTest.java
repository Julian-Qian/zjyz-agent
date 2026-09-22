package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.dao.AgentRuntimeMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentV2FinalizationServiceTest {

    @Test
    void waitingClarificationRunFinalizesWithoutMovingAlreadyPersistedTaskBackwards() {
        AgentRuntimeMapper runtime = mock(AgentRuntimeMapper.class);
        AgentV2Mapper v2 = mock(AgentV2Mapper.class);
        when(runtime.claimRunForCompletion(anyString(), any())).thenReturn(1);
        when(runtime.touchRunHeartbeat(anyString(), any())).thenReturn(1);
        when(runtime.completeRunIfFinalizing(any())).thenReturn(1);
        when(v2.updateRunOutcome(anyString(), anyString())).thenReturn(1);
        AgentV2Models.Task task = task();
        AgentRuntimeRecords.Run run = run();
        AgentRuntimeRecords.Message assistant = new AgentRuntimeRecords.Message();
        assistant.setMessageId("message-1");

        boolean completed = new AgentV2FinalizationService(runtime, v2).finalizeRun(task, run,
                assistant, Collections.emptyList(), "WAITING_USER", "{}", LocalDateTime.now());

        assertTrue(completed);
        verify(v2, never()).updateTaskStatus(anyString(), anyString(), any(), any());
        verify(runtime).completeRunIfFinalizing(run);
    }

    @Test
    void revokedContractCannotBePersistedAfterItsToolCompleted() {
        AgentRuntimeMapper runtime=mock(AgentRuntimeMapper.class);AgentV2Mapper v2=mock(AgentV2Mapper.class);
        when(runtime.claimRunForCompletion(anyString(),any())).thenReturn(1);
        AgentV2FinalizationService service=new AgentV2FinalizationService(runtime,v2);
        com.zjyz.agent.workspace.attachment.AgentAttachmentService attachments=mock(com.zjyz.agent.workspace.attachment.AgentAttachmentService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"attachmentService",attachments);
        org.mockito.Mockito.doThrow(new com.zjyz.common.exception.MyBizException("附件已删除","ATTACHMENT_UNAVAILABLE"))
            .when(attachments).lockAndValidate(anyString(),any());
        AgentRuntimeRecords.Message assistant=new AgentRuntimeRecords.Message();
        assistant.setMetadataJson("{\"documentReview\":{\"attachmentRefs\":[{\"attachmentId\":\"a\"}]}}");
        org.junit.jupiter.api.Assertions.assertThrows(com.zjyz.common.exception.MyBizException.class,()->
            service.finalizeRun(task(),run(),assistant,Collections.emptyList(),"COMPLETED","{}",LocalDateTime.now()));
        verify(runtime,never()).insertMessage(any());verify(runtime,never()).insertArtifact(any());
    }

    private AgentV2Models.Task task() {
        AgentV2Models.Task task = new AgentV2Models.Task();
        task.setTaskId("task-1");
        task.setTurnId("turn-1");
        return task;
    }

    private AgentRuntimeRecords.Run run() {
        AgentRuntimeRecords.Run run = new AgentRuntimeRecords.Run();
        run.setRunId("run-1");
        run.setThreadId("thread-1");
        return run;
    }
}
