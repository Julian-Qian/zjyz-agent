package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.pojo.param.ret.InventoryLedgerItemRet;
import com.zjyz.pojo.param.ret.InventoryLedgerListRet;
import com.zjyz.pojo.param.ret.InventoryMaterialItemRet;
import com.zjyz.pojo.param.ret.InventoryMaterialListRet;
import com.zjyz.service.InventoryService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryLedgerTraceSkillTest {

    private final InventoryService inventoryService = mock(InventoryService.class);
    private final InventoryLedgerTraceSkill skill =
            new InventoryLedgerTraceSkill(inventoryService, new ObjectMapper());

    @Test
    void missingMaterialKeywordAsksClarification() {
        AgentSkillExecution execution = skill.execute("{}", "查库存台账", workspace());
        assertEquals(Boolean.TRUE, execution.getNeedClarification());
        assertTrue(execution.getMissingSlots().contains("materialKeyword"));
    }

    @Test
    void ambiguousKeywordAsksClarificationWithCandidates() {
        InventoryMaterialListRet materials = new InventoryMaterialListRet();
        materials.setList(Arrays.asList(material("m1", "钢管", "48-3"), material("m2", "钢管", "48-2")));
        when(inventoryService.queryInventoryMaterials(any())).thenReturn(materials);

        AgentSkillExecution execution = skill.execute(
                "{\"materialKeyword\":\"钢管\"}", "追溯钢管台账", workspace());
        assertEquals(Boolean.TRUE, execution.getNeedClarification());
        assertTrue(execution.getClarificationQuestion().contains("48-3"));
    }

    @Test
    void tracesLedgerWithBalancesAndTruncationDisclosure() {
        InventoryMaterialListRet materials = new InventoryMaterialListRet();
        materials.setList(Collections.singletonList(material("m1", "钢管", "48-3")));
        when(inventoryService.queryInventoryMaterials(any())).thenReturn(materials);
        InventoryLedgerListRet ledger = new InventoryLedgerListRet();
        ledger.setTotalNum(35L);
        InventoryLedgerItemRet row = new InventoryLedgerItemRet();
        row.setEventTime("2026-08-30 10:00");
        row.setBehaviorName("租出");
        row.setQuantity(100);
        row.setInventoryDelta(-100);
        row.setAfterInventory(220);
        row.setInventoryUnit("根");
        ledger.setList(Collections.singletonList(row));
        when(inventoryService.queryInventoryLedger(any())).thenReturn(ledger);

        AgentSkillExecution execution = skill.execute(
                "{\"materialKeyword\":\"钢管48-3\",\"limit\":1}", "追溯钢管48-3库存台账", workspace());

        assertNotEquals(Boolean.TRUE, execution.getNeedClarification());
        Map<String, Object> card = execution.getCards().get(0);
        assertEquals("inventory-ledger-trace", card.get("type"));
        assertEquals(35L, card.get("totalCount"));
        assertEquals(1, card.get("displayedCount"));
        assertEquals(Boolean.TRUE, card.get("truncated"));
        assertTrue(execution.getAnswer().contains("35 条"));
        assertEquals(35, execution.getEvidence().getRecordCount());
    }

    private InventoryMaterialItemRet material(String id, String name, String spec) {
        InventoryMaterialItemRet ret = new InventoryMaterialItemRet();
        ret.setMaterialId(id);
        ret.setMaterialName(name);
        ret.setMaterialSpecification(spec);
        ret.setInventoryUnit("根");
        ret.setInventoryQuantity(320);
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
