package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.pojo.entity.ProjectEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectCatalogSkillTest {

    @Test
    void effectiveQueryEvidenceSupportsCompletionAndDetectsTruncation() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        when(mapper.selectList(any())).thenReturn(projects());
        ProjectCatalogSkill skill = new ProjectCatalogSkill(mapper,new ObjectMapper());
        AgentSkillExecution complete=skill.execute("{\"managerName\":\"张经理\",\"status\":\"ONGOING\"}","项目清单",workspace());
        assertEquals("张经理",complete.getEvidence().getCriteria().get("managerName"));
        assertEquals("ONGOING",complete.getEvidence().getCriteria().get("status"));
        assertEquals("COMPLETE",complete.getEvidence().getCompleteness());
        assertEquals("AVAILABLE",complete.getEvidence().getAvailability());
        AgentSkillExecution partial=skill.execute("{\"limit\":1}","项目清单",workspace());
        assertEquals("PARTIAL",partial.getEvidence().getCompleteness());
    }

    @Test
    @SuppressWarnings("unchecked")
    void combinedBusinessTypeAndStatusBreakdownQueriesTheWholeScope() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(projectMapper.selectList(any())).thenReturn(projects());
        ProjectCatalogSkill skill = new ProjectCatalogSkill(projectMapper, new ObjectMapper());

        AgentSkillExecution execution = skill.execute(
                "{\"projectBusinessType\":\"rent_in\",\"status\":\"ONGOING\"}",
                "统计当前全部项目中租入、租出、进行中和已完成的数量",
                workspace());

        ArgumentCaptor<QueryWrapper<ProjectEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(projectMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertFalse(sql.contains("project_business_type"));
        assertFalse(sql.contains("project_status_flag"));

        Map<String, Object> card = execution.getCards().get(0);
        assertEquals(10, card.get("total"));
        assertEquals(10L, card.get("rentOutCount"));
        assertEquals(0L, card.get("rentInCount"));
        assertEquals(7L, card.get("ongoingCount"));
        assertEquals(3L, card.get("completedCount"));
        assertTrue(execution.getAnswer().contains("租出 10 个、租入 0 个"));
        assertTrue(execution.getAnswer().contains("进行中 7 个、已完成 3 个"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void individualBusinessTypeAndStatusQuestionsKeepTheirSingleFilter() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        when(projectMapper.selectList(any())).thenReturn(Collections.emptyList());
        ProjectCatalogSkill skill = new ProjectCatalogSkill(projectMapper, new ObjectMapper());

        skill.execute("{}", "租入项目有多少", workspace());
        skill.execute("{}", "租出项目有多少", workspace());
        skill.execute("{}", "进行中项目有多少", workspace());
        skill.execute("{}", "已完成项目有多少", workspace());

        ArgumentCaptor<QueryWrapper<ProjectEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(projectMapper, times(4)).selectList(captor.capture());
        assertFilter(captor.getAllValues().get(0), "project_business_type", "rent_in");
        assertFilter(captor.getAllValues().get(1), "project_business_type", "rent_out");
        assertFilter(captor.getAllValues().get(2), "project_status_flag", "0");
        assertFilter(captor.getAllValues().get(3), "project_status_flag", "1");
    }

    @Test
    void ownerRequestUsesOnlyManagerFieldAndPreservesFrozenScope() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        when(mapper.selectList(any())).thenReturn(Collections.emptyList());
        com.zjyz.agent.workspace.model.AgentRuntimeRecords.Workspace scope = workspace();
        scope.setSelectionMode("EXPLICIT");
        scope.setProjectIds(Collections.singletonList("p-1"));
        AgentSkillExecution result = new ProjectCatalogSkill(mapper, new ObjectMapper()).execute(
                "{\"keyword\":\"何\",\"managerName\":\"何某\",\"startDate\":\"2026-01-01\",\"status\":\"ONGOING\"}",
                "目前小何在负责哪些项目", scope);
        ArgumentCaptor<QueryWrapper<ProjectEntity>> capture = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(capture.capture());
        QueryWrapper<ProjectEntity> query = capture.getValue();
        String sql = query.getSqlSegment();
        assertTrue(sql.contains("manager_name"));
        assertTrue(sql.contains("project_id IN"));
        assertTrue(sql.contains("cid"));
        assertFalse(sql.contains("project_name"));
        assertFalse(sql.contains("partner_name"));
        assertFalse(sql.contains("tenant_unit"));
        assertFalse(sql.contains("project_status_flag"));
        assertFalse(sql.contains("create_date >="));
        assertTrue(query.getParamNameValuePairs().containsValue("%小何%"));
        assertTrue(query.getParamNameValuePairs().containsValue("p-1"));
        assertTrue(result.getAnswer().contains("登记的负责人姓名"));
    }

    @Test
    void explicitPartnerFilterDoesNotSearchOurManagerOrProjectName() {
        ProjectMapper mapper = mock(ProjectMapper.class);
        when(mapper.selectList(any())).thenReturn(Collections.emptyList());
        new ProjectCatalogSkill(mapper, new ObjectMapper()).execute(
                "{\"partnerName\":\"小何\"}", "查对方负责人为小何的项目", workspace());
        ArgumentCaptor<QueryWrapper<ProjectEntity>> capture = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectList(capture.capture());
        assertTrue(capture.getValue().getSqlSegment().contains("partner_name"));
        assertFalse(capture.getValue().getSqlSegment().contains("manager_name"));
    }

    private void assertFilter(QueryWrapper<ProjectEntity> wrapper, String column, String value) {
        assertTrue(wrapper.getSqlSegment().contains(column));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(value));
    }

    private List<ProjectEntity> projects() {
        List<ProjectEntity> projects = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            ProjectEntity project = new ProjectEntity();
            project.setProjectId("p-" + index);
            project.setProjectName("项目" + index);
            project.setProjectBusinessType("rent_out");
            project.setProjectStatusFlag(index < 7 ? "0" : "1");
            project.setCreateDate("2026-08-01");
            projects.add(project);
        }
        return projects;
    }

    private AgentRuntimeRecords.Workspace workspace() {
        AgentRuntimeRecords.Workspace value = new AgentRuntimeRecords.Workspace();
        value.setCid("cid-1");
        value.setScopeType("TENANT");
        value.setSelectionMode("ALL");
        value.setProjectIds(Collections.emptyList());
        return value;
    }
}
