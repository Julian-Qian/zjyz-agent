package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.dao.CompensationDocumentMaterialMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.RentDocumentMapper;
import com.zjyz.dao.RentDocumentMaterialMapper;
import com.zjyz.dao.ReturnDocumentMapper;
import com.zjyz.dao.ReturnDocumentMaterialMapper;
import com.zjyz.pojo.entity.CompensationDocumentMaterialEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.RentDocumentEntity;
import com.zjyz.pojo.entity.RentDocumentMaterialEntity;
import com.zjyz.pojo.entity.ReturnDocumentMaterialEntity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaterialLifecycleSkillTest {

    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final RentDocumentMaterialMapper rentMaterialMapper = mock(RentDocumentMaterialMapper.class);
    private final ReturnDocumentMaterialMapper returnMaterialMapper = mock(ReturnDocumentMaterialMapper.class);
    private final CompensationDocumentMaterialMapper compensationMaterialMapper =
            mock(CompensationDocumentMaterialMapper.class);
    private final RentDocumentMapper rentDocumentMapper = mock(RentDocumentMapper.class);
    private final ReturnDocumentMapper returnDocumentMapper = mock(ReturnDocumentMapper.class);
    private final MaterialLifecycleSkill skill = new MaterialLifecycleSkill(projectMapper,
            rentMaterialMapper, returnMaterialMapper, compensationMaterialMapper,
            rentDocumentMapper, returnDocumentMapper, new ObjectMapper());

    @Test
    void detectsOverReturnWithPreservedNegativeAndCompensationRatePerUnitGroup() {
        ProjectEntity project = new ProjectEntity();
        project.setProjectId("p1");
        project.setProjectName("项目一");
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project));
        when(rentMaterialMapper.selectList(any())).thenReturn(Arrays.asList(
                rent("p1", "钢管", "48-3", "根", 100),
                rent("p1", "扣件", null, "只", 200)));
        when(returnMaterialMapper.selectList(any())).thenReturn(Collections.singletonList(
                returned("p1", "钢管", "48-3", "根", 120)));
        when(compensationMaterialMapper.selectList(any())).thenReturn(Collections.singletonList(
                compensated("p1", "扣件", null, "只", 20)));
        when(rentDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(returnDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());

        AgentSkillExecution execution = skill.execute(
                "{\"analysisType\":\"ALL\",\"minStagnantDays\":60}", "材料多还和赔偿率", workspace());

        Map<String, Object> card = execution.getCards().get(0);
        Map<String, Object> overReturn = (Map<String, Object>) card.get("overReturn");
        assertEquals(1, overReturn.get("totalCount"));
        List<Map<String, Object>> overItems = (List<Map<String, Object>>) overReturn.get("items");
        assertEquals(-20, overItems.get(0).get("outstandingQuantity"), "多还必须保留负差额原值");

        Map<String, Object> compensationRate = (Map<String, Object>) card.get("compensationRate");
        assertEquals(1, compensationRate.get("totalCount"));
        List<Map<String, Object>> rateItems = (List<Map<String, Object>>) compensationRate.get("items");
        assertEquals("只", rateItems.get(0).get("materialUnit"));
        assertEquals(new BigDecimal("0.1000"), rateItems.get(0).get("compensationRate"));
        assertTrue(String.valueOf(card.get("scopeNote")).contains("跨单位"));
    }

    @Test
    void staleOccupancyUsesLastReturnDateAgainstThreshold() {
        ProjectEntity project = new ProjectEntity();
        project.setProjectId("p1");
        project.setProjectName("项目一");
        project.setManagerName("张三");
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project));
        when(rentMaterialMapper.selectList(any())).thenReturn(Collections.singletonList(
                rent("p1", "钢管", "48-3", "根", 100)));
        when(returnMaterialMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(compensationMaterialMapper.selectList(any())).thenReturn(Collections.emptyList());
        RentDocumentEntity lastRent = new RentDocumentEntity();
        lastRent.setRentDate("2026-01-01");
        when(rentDocumentMapper.selectList(any())).thenReturn(Collections.singletonList(lastRent));
        when(returnDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());

        AgentSkillExecution execution = skill.execute(
                "{\"analysisType\":\"STALE_OCCUPANCY\",\"minStagnantDays\":60}", "哪些项目只租不还", workspace());

        Map<String, Object> card = execution.getCards().get(0);
        Map<String, Object> stale = (Map<String, Object>) card.get("staleOccupancy");
        assertEquals(1, stale.get("totalCount"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) stale.get("items");
        assertEquals("项目一", items.get(0).get("projectName"));
        assertTrue((Long) items.get(0).get("staleDays") >= 60L);
        assertEquals(0, ((Map<String, Object>) card.get("overReturn")).get("totalCount"),
                "非请求分区不输出数据");
    }

    private RentDocumentMaterialEntity rent(String projectId, String name, String spec, String unit, int qty) {
        RentDocumentMaterialEntity entity = new RentDocumentMaterialEntity();
        entity.setProjectId(projectId);
        entity.setMaterialName(name);
        entity.setMaterialSpecification(spec);
        entity.setMaterialUnit(unit);
        entity.setMaterialNumber(qty);
        return entity;
    }

    private ReturnDocumentMaterialEntity returned(String projectId, String name, String spec, String unit, int qty) {
        ReturnDocumentMaterialEntity entity = new ReturnDocumentMaterialEntity();
        entity.setProjectId(projectId);
        entity.setMaterialName(name);
        entity.setMaterialSpecification(spec);
        entity.setMaterialUnit(unit);
        entity.setMaterialNumber(qty);
        return entity;
    }

    private CompensationDocumentMaterialEntity compensated(String projectId, String name, String spec,
                                                           String unit, int qty) {
        CompensationDocumentMaterialEntity entity = new CompensationDocumentMaterialEntity();
        entity.setProjectId(projectId);
        entity.setMaterialName(name);
        entity.setMaterialSpecification(spec);
        entity.setMaterialUnit(unit);
        entity.setMaterialNumber(qty);
        return entity;
    }

    private AgentRuntimeRecords.Workspace workspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("c1");
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("ALL");
        return workspace;
    }
}
