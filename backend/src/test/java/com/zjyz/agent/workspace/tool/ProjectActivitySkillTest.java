package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.ReconciliationDocumentMapper;
import com.zjyz.dao.RentDocumentMapper;
import com.zjyz.dao.RentInDocumentMapper;
import com.zjyz.dao.RentInReturnDocumentMapper;
import com.zjyz.dao.ReturnDocumentMapper;
import com.zjyz.dao.SettlementDocumentMapper;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.RentDocumentEntity;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectActivitySkillTest {

    @Test
    @SuppressWarnings("unchecked")
    void findsOngoingProjectWithoutRecentRentOrReturnActivity() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        RentDocumentMapper rentMapper = mock(RentDocumentMapper.class);
        ReturnDocumentMapper returnMapper = mock(ReturnDocumentMapper.class);
        RentInDocumentMapper rentInMapper = mock(RentInDocumentMapper.class);
        RentInReturnDocumentMapper rentInReturnMapper = mock(RentInReturnDocumentMapper.class);
        ReconciliationDocumentMapper reconciliationMapper = mock(ReconciliationDocumentMapper.class);
        SettlementDocumentMapper settlementMapper = mock(SettlementDocumentMapper.class);
        ProjectEntity project = new ProjectEntity();
        project.setProjectId("p-1");
        project.setProjectName("长期无活动项目");
        project.setProjectStatusFlag("0");
        project.setCreateDate("2026-01-01");
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project));
        RentDocumentEntity rent = new RentDocumentEntity();
        rent.setProjectId("p-1");
        rent.setRentDate("2026-07-01");
        when(rentMapper.selectList(any())).thenReturn(Collections.singletonList(rent));
        when(returnMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentInMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentInReturnMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(reconciliationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(settlementMapper.selectList(any())).thenReturn(Collections.emptyList());

        AgentSkillExecution execution = new ProjectActivitySkill(projectMapper, rentMapper, returnMapper, rentInMapper,
                rentInReturnMapper, reconciliationMapper, settlementMapper, new ObjectMapper()).execute(
                "{\"asOfDate\":\"2026-08-24\",\"minInactiveDays\":30,\"projectStatus\":\"ONGOING\","
                        + "\"activityTypes\":[\"RENT_OUT\",\"RETURN\"]}",
                "进行中项目最近30天没有租出或归还", workspace());

        Map<String, Object> card = execution.getCards().get(0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) card.get("items");
        assertEquals(54L, items.get(0).get("inactiveDays"));
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
