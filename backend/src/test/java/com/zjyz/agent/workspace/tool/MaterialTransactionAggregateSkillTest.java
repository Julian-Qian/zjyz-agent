package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.dao.CompensationDocumentMapper;
import com.zjyz.dao.CompensationDocumentMaterialMapper;
import com.zjyz.dao.RentDocumentMapper;
import com.zjyz.dao.RentDocumentMaterialMapper;
import com.zjyz.dao.RentInDocumentMapper;
import com.zjyz.dao.RentInDocumentMaterialMapper;
import com.zjyz.dao.RentInReturnDocumentMapper;
import com.zjyz.dao.RentInReturnDocumentMaterialMapper;
import com.zjyz.dao.ReturnDocumentMapper;
import com.zjyz.dao.ReturnDocumentMaterialMapper;
import com.zjyz.pojo.entity.RentDocumentEntity;
import com.zjyz.pojo.entity.RentDocumentMaterialEntity;
import com.zjyz.pojo.entity.RentInDocumentEntity;
import com.zjyz.pojo.entity.RentInDocumentMaterialEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaterialTransactionAggregateSkillTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "今年帮我赚到最多钱的材料是什么", "哪种材料租金收入最高", "材料利润排行",
            "材料回款排行", "材料租出数量和赚钱金额都比较一下"})
    void financialQuestionsCannotExecuteQuantityQueryEvenWhenModelForcesQuantity(String message) {
        Dependencies dependencies = new Dependencies();
        com.zjyz.common.exception.MyBizException error = org.junit.jupiter.api.Assertions.assertThrows(
                com.zjyz.common.exception.MyBizException.class,
                () -> dependencies.skill().execute("{\"metric\":\"QUANTITY\"}", message, allProjectsWorkspace()));
        assertEquals("AGT_METRIC_MISMATCH", error.getErrorCode());
        org.mockito.Mockito.verifyNoInteractions(dependencies.rentDocumentMapper, dependencies.rentMaterialMapper);
    }


    @Test
    @SuppressWarnings("unchecked")
    void aggregatesRentOutAcrossProjectsAndSpecifications() {
        Dependencies dependencies = new Dependencies();
        RentDocumentEntity first = rentDocument("d-1", "p-1", "2026-02-01");
        RentDocumentEntity second = rentDocument("d-2", "p-2", "2026-03-01");
        when(dependencies.rentDocumentMapper.selectList(any())).thenReturn(Arrays.asList(first, second));

        RentDocumentMaterialEntity steelOne = rentMaterial("d-1", "p-1", "钢管", "48x3.0", "根", 10d);
        RentDocumentMaterialEntity steelTwo = rentMaterial("d-2", "p-2", "钢管", "48x3.5", "根", 6d);
        RentDocumentMaterialEntity fastener = rentMaterial("d-1", "p-1", "扣件", "十字", "根", 12d);
        when(dependencies.rentMaterialMapper.selectList(any())).thenReturn(Arrays.asList(steelOne, steelTwo, fastener));

        AgentSkillExecution execution = dependencies.skill().execute(
                "{\"flowType\":\"RENT_OUT\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-08-25\",\"groupBy\":\"MATERIAL_NAME\",\"limit\":10}",
                "今年我租出最多的材料有哪些？",
                allProjectsWorkspace());

        Map<String, Object> card = execution.getCards().get(0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) card.get("items");
        assertEquals("material-transaction-ranking", card.get("type"));
        assertEquals("钢管", items.get(0).get("materialName"));
        assertEquals(16L, items.get(0).get("totalQuantity"));
        assertEquals(2, items.get(0).get("specificationCount"));
        assertEquals(2, items.get(0).get("projectCount"));
        assertEquals(2, card.get("totalCount"));
        assertEquals(2, card.get("displayedCount"));
        assertFalse((Boolean) card.get("truncated"));
        assertEquals(28L, card.get("totalQuantity"));
        assertTrue(execution.getAnswer().startsWith("今年，"));
        assertTrue(execution.getAnswer().contains("明细见结果卡"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void separatesHeterogeneousUnitsAndNeverInventsMissingUnit() {
        Dependencies dependencies = new Dependencies();
        RentDocumentEntity document = rentDocument("d-1", "p-1", "2026-03-01");
        when(dependencies.rentDocumentMapper.selectList(any())).thenReturn(Collections.singletonList(document));
        when(dependencies.rentMaterialMapper.selectList(any())).thenReturn(Arrays.asList(
                rentMaterial("d-1", "p-1", "钢管", "A", "根", 100d),
                rentMaterial("d-1", "p-1", "立杆", "B", "根", 20d),
                rentMaterial("d-1", "p-1", "型钢", "C", "吨", 2d),
                rentMaterial("d-1", "p-1", "扣件", "D", "件", 300d),
                rentMaterial("d-1", "p-1", "未知材料", "E", null, 9d)));

        AgentSkillExecution execution = dependencies.skill().execute(
                "{\"flowType\":\"RENT_OUT\",\"groupBy\":\"MATERIAL_NAME\",\"limit\":1}",
                "租出材料排行", allProjectsWorkspace());

        Map<String, Object> card = execution.getCards().get(0);
        List<Map<String, Object>> unitGroups = (List<Map<String, Object>>) card.get("unitGroups");
        List<Map<String, Object>> legacyItems = (List<Map<String, Object>>) card.get("items");
        assertEquals(5, card.get("totalCount"));
        assertEquals(4, card.get("displayedCount"));
        assertTrue((Boolean) card.get("truncated"));
        assertEquals(1, card.get("limit"));
        assertEquals(4, card.get("unitCount"));
        assertTrue((Boolean) card.get("hasMissingUnit"));
        assertNull(card.get("totalQuantity"));
        assertEquals(4, unitGroups.size());
        assertTrue(unitGroups.stream().anyMatch(group -> "根".equals(group.get("unit"))));
        assertTrue(unitGroups.stream().anyMatch(group -> "吨".equals(group.get("unit"))));
        assertTrue(unitGroups.stream().anyMatch(group -> "件".equals(group.get("unit"))));
        assertTrue(unitGroups.stream().anyMatch(group -> "未标注单位".equals(group.get("unit"))));
        assertTrue(unitGroups.stream().anyMatch(group -> Boolean.TRUE.equals(group.get("unitMissing"))));
        assertTrue(legacyItems.isEmpty());
        assertTrue(String.valueOf(card.get("aggregationWarning")).contains("不可直接相加"));
        assertTrue(String.valueOf(card.get("aggregationWarning")).contains("未标注单位"));
        assertTrue(execution.getAnswer().contains("不同单位不能互相比较"));
        assertTrue(execution.getAnswer().contains("结果已按 4 种计量单位分别排序"));
        assertFalse(execution.getAnswer().contains("单位根组内前1"));
        assertFalse(execution.getAnswer().contains("单位吨组内前1"));
        assertFalse(execution.getAnswer().contains("按数量总榜"));
        assertFalse(execution.getAnswer().contains("全局最多"));
        assertFalse(execution.getAnswer().matches("(?s).*排列。前\\d+项.*"));
    }

    @Test
    void directlyAnswersSingularTopMaterialQuestionWithoutDumpingTheRanking() {
        Dependencies dependencies = new Dependencies();
        when(dependencies.rentDocumentMapper.selectList(any())).thenReturn(Collections.singletonList(
                rentDocument("d-1", "p-1", "2026-03-01")));
        when(dependencies.rentMaterialMapper.selectList(any())).thenReturn(Arrays.asList(
                rentMaterial("d-1", "p-1", "钢管", "A", "根", 100d),
                rentMaterial("d-1", "p-1", "扣件", "B", "根", 20d)));

        AgentSkillExecution execution = dependencies.skill().execute(
                "{\"flowType\":\"RENT_OUT\",\"groupBy\":\"MATERIAL_NAME\",\"limit\":10}",
                "我今年租出最多的材料是哪一种", allProjectsWorkspace());

        assertEquals("今年，租出数量最多的材料是钢管（100根）。", execution.getAnswer());
        assertFalse(execution.getAnswer().contains("当前项目范围"));
        assertFalse(execution.getAnswer().contains("单位根组内"));
    }

    @Test
    void singularTopQuestionWithMixedUnitsGivesAConciseSafeAnswer() {
        Dependencies dependencies = new Dependencies();
        when(dependencies.rentDocumentMapper.selectList(any())).thenReturn(Collections.singletonList(
                rentDocument("d-1", "p-1", "2026-03-01")));
        when(dependencies.rentMaterialMapper.selectList(any())).thenReturn(Arrays.asList(
                rentMaterial("d-1", "p-1", "钢管", "A", "根", 100d),
                rentMaterial("d-1", "p-1", "型钢", "B", "吨", 2d),
                rentMaterial("d-1", "p-1", "扣件", "C", "只", 300d)));

        AgentSkillExecution execution = dependencies.skill().execute(
                "{\"flowType\":\"RENT_OUT\",\"groupBy\":\"MATERIAL_NAME\",\"limit\":10}",
                "我今年租出最多的材料是哪一种", allProjectsWorkspace());

        assertTrue(execution.getAnswer().startsWith("今年，租出材料使用了多种计量单位，不能直接选出唯一第一名。"));
        assertTrue(execution.getAnswer().contains("根：钢管（100根）"));
        assertTrue(execution.getAnswer().contains("吨：型钢（2吨）"));
        assertTrue(execution.getAnswer().contains("只：扣件（300只）"));
        assertFalse(execution.getAnswer().contains("数据依据"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void infersRentInFlowAndCurrentYearFromMessage() {
        Dependencies dependencies = new Dependencies();
        RentInDocumentEntity document = new RentInDocumentEntity();
        document.setRentInDocumentId("ri-1");
        document.setProjectId("p-1");
        document.setRentInDate("2026-05-01");
        when(dependencies.rentInDocumentMapper.selectList(any())).thenReturn(Collections.singletonList(document));

        RentInDocumentMaterialEntity material = new RentInDocumentMaterialEntity();
        material.setDocumentId("ri-1");
        material.setProjectId("p-1");
        material.setMaterialName("盘扣");
        material.setMaterialSpecification("B型");
        material.setCountingUnit("件");
        material.setCountingQuantity(8d);
        when(dependencies.rentInMaterialMapper.selectList(any())).thenReturn(Collections.singletonList(material));

        AgentSkillExecution execution = dependencies.skill().execute("{}", "今年租入最多的材料", allProjectsWorkspace());

        Map<String, Object> card = execution.getCards().get(0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) card.get("items");
        assertEquals("RENT_IN", card.get("flowType"));
        assertEquals(LocalDate.now(ZoneId.of("Asia/Shanghai")).getYear() + "-01-01", card.get("startDate"));
        assertEquals("盘扣", items.get(0).get("materialName"));
    }

    private RentDocumentEntity rentDocument(String id, String projectId, String date) {
        RentDocumentEntity value = new RentDocumentEntity();
        value.setRentDocumentId(id);
        value.setProjectId(projectId);
        value.setRentDate(date);
        return value;
    }

    private RentDocumentMaterialEntity rentMaterial(String documentId, String projectId, String name,
                                                      String specification, String unit, double quantity) {
        RentDocumentMaterialEntity value = new RentDocumentMaterialEntity();
        value.setDocumentId(documentId);
        value.setProjectId(projectId);
        value.setMaterialName(name);
        value.setMaterialSpecification(specification);
        value.setCountingUnit(unit);
        value.setCountingQuantity(quantity);
        value.setMaterialBusinessType("RENTAL");
        return value;
    }

    private AgentRuntimeRecords.Workspace allProjectsWorkspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("cid-1");
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("ALL");
        workspace.setProjectIds(Collections.emptyList());
        return workspace;
    }

    private static class Dependencies {
        private final RentDocumentMapper rentDocumentMapper = mock(RentDocumentMapper.class);
        private final RentDocumentMaterialMapper rentMaterialMapper = mock(RentDocumentMaterialMapper.class);
        private final ReturnDocumentMapper returnDocumentMapper = mock(ReturnDocumentMapper.class);
        private final ReturnDocumentMaterialMapper returnMaterialMapper = mock(ReturnDocumentMaterialMapper.class);
        private final CompensationDocumentMapper compensationDocumentMapper = mock(CompensationDocumentMapper.class);
        private final CompensationDocumentMaterialMapper compensationMaterialMapper = mock(CompensationDocumentMaterialMapper.class);
        private final RentInDocumentMapper rentInDocumentMapper = mock(RentInDocumentMapper.class);
        private final RentInDocumentMaterialMapper rentInMaterialMapper = mock(RentInDocumentMaterialMapper.class);
        private final RentInReturnDocumentMapper rentInReturnDocumentMapper = mock(RentInReturnDocumentMapper.class);
        private final RentInReturnDocumentMaterialMapper rentInReturnMaterialMapper = mock(RentInReturnDocumentMaterialMapper.class);

        private Dependencies() {
            when(rentDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
            when(returnDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
            when(compensationDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
            when(rentInDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
            when(rentInReturnDocumentMapper.selectList(any())).thenReturn(Collections.emptyList());
        }

        private MaterialTransactionAggregateSkill skill() {
            return new MaterialTransactionAggregateSkill(
                    rentDocumentMapper, rentMaterialMapper,
                    returnDocumentMapper, returnMaterialMapper,
                    compensationDocumentMapper, compensationMaterialMapper,
                    rentInDocumentMapper, rentInMaterialMapper,
                    rentInReturnDocumentMapper, rentInReturnMaterialMapper,
                    new ObjectMapper());
        }
    }
}
