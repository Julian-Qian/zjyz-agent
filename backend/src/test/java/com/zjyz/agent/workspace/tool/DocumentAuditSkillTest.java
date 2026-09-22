package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.CompensationDocumentMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.RentDocumentMapper;
import com.zjyz.dao.RentInDocumentMapper;
import com.zjyz.dao.RentInReturnDocumentMapper;
import com.zjyz.dao.ReturnDocumentMapper;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.RentDocumentEntity;
import com.zjyz.pojo.entity.ReturnDocumentEntity;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentAuditSkillTest {

    private final RentDocumentMapper rentDocumentMapper = mock(RentDocumentMapper.class);
    private final ReturnDocumentMapper returnDocumentMapper = mock(ReturnDocumentMapper.class);
    private final CompensationDocumentMapper compensationDocumentMapper = mock(CompensationDocumentMapper.class);
    private final RentInDocumentMapper rentInDocumentMapper = mock(RentInDocumentMapper.class);
    private final RentInReturnDocumentMapper rentInReturnDocumentMapper = mock(RentInReturnDocumentMapper.class);
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final DocumentAuditSkill skill = new DocumentAuditSkill(rentDocumentMapper, returnDocumentMapper,
            compensationDocumentMapper, rentInDocumentMapper, rentInReturnDocumentMapper,
            projectMapper, new ObjectMapper());

    @Test
    void aggregatesIssuesAcrossTypesWithCountsAndScopeNote() {
        RentDocumentEntity unreviewed = new RentDocumentEntity();
        unreviewed.setRentDocumentId("rd1");
        unreviewed.setRentDocumentName("租出单-001");
        unreviewed.setProjectId("p1");
        unreviewed.setRentDate("2026-08-30");
        unreviewed.setReviewStatus(0);
        when(rentDocumentMapper.selectList(any())).thenReturn(Collections.singletonList(unreviewed));
        ReturnDocumentEntity missingDate = new ReturnDocumentEntity();
        missingDate.setReturnDocumentId("rt1");
        missingDate.setReturnDocumentName("归还单-002");
        missingDate.setProjectId("p1");
        missingDate.setReviewStatus(1);
        missingDate.setReturnDate(null);
        when(returnDocumentMapper.selectList(any())).thenReturn(Collections.singletonList(missingDate));
        when(compensationDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentInDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentInReturnDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
        ProjectEntity project = new ProjectEntity();
        project.setProjectId("p1");
        project.setProjectName("示例项目");
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project));

        AgentSkillExecution execution = skill.execute(null, "有哪些单据还没复核", workspace("ALL", null));

        Map<String, Object> card = execution.getCards().get(0);
        assertEquals("document-audit-list", card.get("type"));
        assertEquals(2, card.get("totalCount"));
        assertEquals(Boolean.FALSE, card.get("truncated"));
        Map<String, Integer> countsByIssue = (Map<String, Integer>) card.get("countsByIssue");
        assertEquals(1, countsByIssue.get("UNREVIEWED"));
        assertEquals(1, countsByIssue.get("MISSING_DATE"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) card.get("items");
        assertEquals("示例项目", items.get(0).get("projectName"));
        assertTrue(String.valueOf(card.get("scopeNote")).contains("v1 审核规则"));
        assertTrue(execution.getAnswer().contains("共 2 张"));
        assertEquals(2, execution.getEvidence().getRecordCount());
    }

    @Test
    void reviewedDocumentWithDateIsNotAnIssue() {
        RentDocumentEntity reviewed = new RentDocumentEntity();
        reviewed.setRentDocumentId("rd2");
        reviewed.setRentDate("2026-08-01");
        reviewed.setReviewStatus(1);
        when(rentDocumentMapper.selectList(any())).thenReturn(Collections.singletonList(reviewed));
        when(returnDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(compensationDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentInDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentInReturnDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());

        AgentSkillExecution execution = skill.execute(null, "待审核单据", workspace("ALL", null));
        Map<String, Object> card = execution.getCards().get(0);
        assertEquals(0, card.get("totalCount"));
        assertTrue(execution.getAnswer().contains("没有命中审核规则"));
    }

    @Test
    void rejectsUnknownDocumentTypeAndEmptyExplicitScope() {
        assertThrows(MyBizException.class, () ->
                skill.execute("{\"documentTypes\":[\"MADE_UP\"]}", "审核", workspace("ALL", null)));
        assertThrows(MyBizException.class, () ->
                skill.execute(null, "待审核单据", workspace("EXPLICIT", Collections.emptyList())));
    }

    private AgentRuntimeRecords.Workspace workspace(String selectionMode, List<String> projectIds) {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("c1");
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode(selectionMode);
        workspace.setProjectIds(projectIds == null ? Arrays.asList("p1", "p2") : projectIds);
        return workspace;
    }
}
