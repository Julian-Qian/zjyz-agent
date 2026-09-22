package com.zjyz.agent.workspace.routing;

import com.zjyz.agent.workspace.tool.AgentToolCatalog;
import com.zjyz.agent.workspace.tool.AgentToolCodes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIntentToolBindingTableTest {

    private final AgentIntentToolBindingTable table = AgentIntentToolBindingTable.instance();

    @Test
    void bindingVersionAndSemanticStatementsPresent() {
        assertEquals("intent-tool-binding-v1", table.bindingVersion());
        assertEquals(6, table.semanticExclusionStatements().size());
        assertTrue(table.semanticExclusionPrompt().contains("付款逾期"));
    }

    @Test
    void ruleToolCodesAreCanonicalAndRegistered() {
        AgentToolCatalog catalog = new AgentToolCatalog();
        for (AgentIntentToolBindingTable.Rule rule : table.rules()) {
            for (String code : rule.requiredToolCodes) {
                assertEquals(code, AgentToolCodes.canonicalize(code),
                        "绑定表必须使用 canonical code: " + code);
                assertEquals(code, catalog.canonicalToolCode(code),
                        "绑定表工具必须已在 Catalog 注册: " + code);
            }
        }
    }

    @Test
    void receivableEnforcementBindsCollectionListInEnterpriseScope() {
        assertEquals(Arrays.asList(AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST),
                table.floorToolCodes("哪些项目需要催缴租金", "ALL"));
        assertEquals(Arrays.asList(AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST),
                table.floorToolCodes("给我到期未付的欠款项目", "MULTI"));
    }

    @Test
    void receivableRulesRespectScopeAndDirection() {
        assertTrue(table.floorToolCodes("哪些项目需要催缴租金", "SINGLE").isEmpty(),
                "单项目范围不强制企业级催缴清单");
        assertTrue(table.floorToolCodes("哪些供应商应付需要催缴", "ALL").isEmpty(),
                "供应商应付方向不得绑定客户应收工具");
        assertTrue(table.floorToolCodes("催缴功能怎么用", "ALL").isEmpty(),
                "帮助类问题不绑定业务工具");
    }

    @Test
    void periodMaterialFlowBindsTransactionAggregate() {
        assertEquals(Arrays.asList(AgentToolCodes.MATERIAL_TRANSACTION_AGGREGATE),
                table.floorToolCodes("本月租出数量最多的材料是哪些", "ALL"));
        assertEquals(Arrays.asList(AgentToolCodes.MATERIAL_TRANSACTION_AGGREGATE),
                table.floorToolCodes("今年赔偿了多少次", "SINGLE"));
    }

    @Test
    void materialFlowRuleRespectsSemanticExclusions() {
        assertTrue(table.floorToolCodes("未归还材料数量有多少", "ALL").isEmpty(),
                "当前占用问题不得绑定期间流水聚合");
        assertTrue(table.floorToolCodes("本月赔偿金额是多少", "ALL").isEmpty(),
                "赔偿金额是财务口径，不绑定材料流水聚合");
    }

    @Test
    void reconciliationGapBindsReconciliationDue() {
        assertEquals(Arrays.asList(AgentToolCodes.PROJECT_RECONCILIATION_DUE),
                table.floorToolCodes("哪些项目还有未对账的区间", "ALL"));
        assertTrue(table.floorToolCodes("逾期付款算不算未对账", "ALL").isEmpty(),
                "混合语义问题交给代码侧解析，下限保持保守");
    }

    @Test
    void applyFloorMergesWithoutDuplicates() {
        List<String> merged = table.applyFloor("哪些项目需要催缴租金", "ALL",
                Arrays.asList(AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST,
                        AgentToolCodes.PROJECT_RECONCILIATION_DUE));
        assertEquals(Arrays.asList(AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST,
                AgentToolCodes.PROJECT_RECONCILIATION_DUE), merged);
        assertFalse(table.applyFloor(null, "ALL", null).contains(null));
    }
}
