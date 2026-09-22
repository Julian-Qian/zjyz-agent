package com.zjyz.agent.workspace.context;

import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentProjectScopeSnapshotTest {

    @Test
    void freezesWorkspaceProjectIdsAndRejectsOutOfScopeCard() {
        AgentRuntimeRecords.Workspace workspace = workspace("project-1");
        AgentProjectScopeSnapshot snapshot = AgentProjectScopeSnapshot.capture(workspace);
        workspace.setProjectIds(Arrays.asList("project-1", "project-2"));

        AgentSkillExecution execution = executionWithProject("project-2");
        MyBizException error = assertThrows(MyBizException.class,
                () -> snapshot.validateExecution(execution));

        assertEquals("AGT_SCOPE_VIOLATION", error.getErrorCode());
        assertEquals(Collections.singletonList("project-1"), snapshot.getProjectIds());
    }

    @Test
    void acceptsNestedCardsWhenEveryProjectIsWithinExplicitScope() {
        AgentProjectScopeSnapshot snapshot = AgentProjectScopeSnapshot.capture(workspace("project-1"));

        assertDoesNotThrow(() -> snapshot.validateExecution(executionWithProject("project-1")));
    }

    @Test
    void rejectsOutOfScopeAnswerEvidenceAndArtifactContent() {
        AgentProjectScopeSnapshot snapshot = AgentProjectScopeSnapshot.capture(workspace("project-1"));

        AgentSkillExecution answer = executionWithProject("project-1");
        answer.setAnswer("项目ID：project-2 存在异常");
        AgentSkillExecution evidence = executionWithProject("project-1");
        AgentEvidence rawEvidence = new AgentEvidence();
        rawEvidence.setProjectIds(Collections.singletonList("project-2"));
        evidence.setEvidence(rawEvidence);
        AgentSkillExecution artifact = executionWithProject("project-1");
        artifact.setArtifactContentJson("{\"items\":[{\"projectId\":\"project-2\"}]}");

        assertEquals("AGT_SCOPE_VIOLATION",
                assertThrows(MyBizException.class, () -> snapshot.validateExecution(answer)).getErrorCode());
        assertEquals("AGT_SCOPE_VIOLATION",
                assertThrows(MyBizException.class, () -> snapshot.validateExecution(evidence)).getErrorCode());
        assertEquals("AGT_SCOPE_VIOLATION",
                assertThrows(MyBizException.class, () -> snapshot.validateExecution(artifact)).getErrorCode());
    }

    @Test
    void helpAnswerExamplesSkipTextScanButStructuredScopeRemainsEnforced() {
        AgentProjectScopeSnapshot snapshot = AgentProjectScopeSnapshot.capture(workspace("project-1"));
        AgentSkillExecution helpExample = new AgentSkillExecution();
        helpExample.setAnswer("示例参数：projectId=project-2");
        AgentSkillExecution structuredLeak = executionWithProject("project-2");
        structuredLeak.setAnswer("帮助示例");

        assertDoesNotThrow(() -> snapshot.validateExecution(helpExample, false));
        assertEquals("AGT_SCOPE_VIOLATION", assertThrows(MyBizException.class,
                () -> snapshot.validateExecution(structuredLeak, false)).getErrorCode());
    }

    private AgentRuntimeRecords.Workspace workspace(String projectId) {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setWorkspaceId("workspace-1");
        workspace.setCid("cid-1");
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("EXPLICIT");
        workspace.setProjectId(projectId);
        workspace.setProjectIds(Collections.singletonList(projectId));
        return workspace;
    }

    private AgentSkillExecution executionWithProject(String projectId) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("projectId", projectId);
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("items", Collections.singletonList(item));
        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setCards(Collections.singletonList(card));
        return execution;
    }
}
