package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.pojo.param.req.QueryInventoryAnomalyParam;
import com.zjyz.pojo.param.ret.InventoryAnomalyListRet;
import com.zjyz.pojo.param.ret.InventoryAnomalyRet;
import com.zjyz.pojo.param.ret.InventoryDashboardRet;
import com.zjyz.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryOperationsSkillTest {

    private final InventoryService inventoryService = mock(InventoryService.class);
    private final InventoryOperationsSkill skill =
            new InventoryOperationsSkill(inventoryService, new ObjectMapper());

    @Test
    void returnsFullSetCountWithTruncationDisclosure() {
        InventoryDashboardRet dashboard = new InventoryDashboardRet();
        dashboard.getAnomalySummary().setTotalAnomalyMaterials(9);
        dashboard.getAnomalySummary().setNegativeInventoryCount(7);
        when(inventoryService.queryInventoryDashboard(null, null)).thenReturn(dashboard);
        InventoryAnomalyListRet anomalies = new InventoryAnomalyListRet();
        anomalies.setTotalNum(7L);
        anomalies.setList(Arrays.asList(anomaly("钢管", "NEGATIVE_INVENTORY", -12),
                anomaly("扣件", "NEGATIVE_INVENTORY", -3)));
        when(inventoryService.queryInventoryAnomalies(any())).thenReturn(anomalies);

        AgentSkillExecution execution = skill.execute(
                "{\"anomalyCode\":\"NEGATIVE_INVENTORY\",\"limit\":2}", "有哪些负库存材料", workspace());

        Map<String, Object> card = execution.getCards().get(0);
        assertEquals("inventory-operations-summary", card.get("type"));
        assertEquals(7L, card.get("totalCount"));
        assertEquals(2, card.get("displayedCount"));
        assertEquals(Boolean.TRUE, card.get("truncated"));
        assertTrue(execution.getAnswer().contains("7 项"));
        assertTrue(execution.getAnswer().contains("展示前 2 项"));
        assertTrue(String.valueOf(card.get("scopeNote")).contains("跨单位"));
        assertEquals(7, execution.getEvidence().getRecordCount());

        ArgumentCaptor<QueryInventoryAnomalyParam> captor =
                ArgumentCaptor.forClass(QueryInventoryAnomalyParam.class);
        org.mockito.Mockito.verify(inventoryService).queryInventoryAnomalies(captor.capture());
        assertEquals("NEGATIVE_INVENTORY", captor.getValue().getAnomalyCode());
    }

    @Test
    void rejectsUnknownAnomalyCode() {
        assertThrows(MyBizException.class, () ->
                skill.execute("{\"anomalyCode\":\"MADE_UP\"}", "库存异常", workspace()));
    }

    @Test
    void messageKeywordFallsBackToNegativeInventoryFilter() {
        when(inventoryService.queryInventoryDashboard(null, null)).thenReturn(new InventoryDashboardRet());
        InventoryAnomalyListRet empty = new InventoryAnomalyListRet();
        empty.setTotalNum(0L);
        when(inventoryService.queryInventoryAnomalies(any())).thenReturn(empty);

        AgentSkillExecution execution = skill.execute(null, "有没有负库存的材料", workspace());

        ArgumentCaptor<QueryInventoryAnomalyParam> captor =
                ArgumentCaptor.forClass(QueryInventoryAnomalyParam.class);
        org.mockito.Mockito.verify(inventoryService).queryInventoryAnomalies(captor.capture());
        assertEquals("NEGATIVE_INVENTORY", captor.getValue().getAnomalyCode());
        assertTrue(execution.getAnswer().contains("没有匹配记录"));
    }

    private InventoryAnomalyRet anomaly(String name, String code, int currentValue) {
        InventoryAnomalyRet ret = new InventoryAnomalyRet();
        ret.setMaterialId("m-" + name);
        ret.setMaterialName(name);
        ret.setAnomalyCode(code);
        ret.setAnomalyName("库存为负");
        ret.setSeverity("HIGH");
        ret.setCurrentValue(currentValue);
        return ret;
    }

    private AgentRuntimeRecords.Workspace workspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("c1");
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("ALL");
        return workspace;
    }
}
