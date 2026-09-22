package com.zjyz.agent.workspace.tool;

import com.zjyz.agent.workspace.context.AgentTaskFrame;
import com.zjyz.agent.workspace.context.AgentTaskCoverage;
import com.zjyz.agent.orch.AgentIntentType;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeContext;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolCatalogTest {

    @Test
    void exposesOnlyRelevantAndAuthorizedTools() {
        AgentToolCatalog catalog = new AgentToolCatalog();
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("ALL");
        workspace.setProjectIds(Collections.emptyList());
        workspace.setFinanceEnabled(false);
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setDomain("SETTLEMENT");
        frame.setUserGoal("8月份有哪些项目应该进行对账了？");
        AgentKnowledgeContext knowledge = new AgentKnowledgeContext();
        knowledge.getToolHints().add("project.reconciliation_due");

        List<Map<String, Object>> definitions = catalog.modelDefinitions(workspace, frame, knowledge);

        assertTrue(definitions.size() <= 6);
        assertTrue(definitions.stream().anyMatch(item -> "project_reconciliation_due".equals(functionName(item))));
        assertFalse(definitions.stream().anyMatch(item -> "project_get_summary".equals(functionName(item))));
        assertFalse(definitions.stream().anyMatch(item -> "finance_receivable_collection_list".equals(functionName(item))));
    }

    @Test
    void prioritizesAllRequiredToolsForCrossDomainQuestion() {
        AgentToolCatalog catalog = new AgentToolCatalog();
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("ALL");
        workspace.setProjectIds(Collections.emptyList());
        workspace.setFinanceEnabled(true);
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setDomain("FINANCE");
        frame.setUserGoal("超过30天未对账且仍有应收欠款");
        frame.setRequiredToolCodes(java.util.Arrays.asList(
                "project.reconciliation_due",
                "finance.receivable_collection_list"));

        List<Map<String, Object>> definitions = catalog.modelDefinitions(workspace, frame, new AgentKnowledgeContext());

        assertTrue(definitions.stream().anyMatch(item -> "project_reconciliation_due".equals(functionName(item))));
        assertTrue(definitions.stream().anyMatch(item -> "finance_receivable_collection_list".equals(functionName(item))));
    }

    @Test
    void exposesMaterialAggregateForMaterialRanking() {
        AgentToolCatalog catalog = new AgentToolCatalog();
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("ALL");
        workspace.setProjectIds(Collections.emptyList());
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setDomain("MATERIAL");
        frame.setUserGoal("今年我租出最多的材料有哪些？");
        frame.setRequiredToolCodes(Collections.singletonList("material.transaction_aggregate"));

        List<Map<String, Object>> definitions = catalog.modelDefinitions(workspace, frame, new AgentKnowledgeContext());

        assertTrue(definitions.stream().anyMatch(item -> "material_transaction_aggregate".equals(functionName(item))));
    }

    @Test
    void resolvesModelNamesCanonicalCodesAndLegacyAliasesToOneDescriptor() {
        AgentToolCatalog catalog = new AgentToolCatalog();

        AgentToolDescriptor canonical = catalog.find("finance.receivable_collection_list");

        assertSame(canonical, catalog.find("finance_receivable_collection_list"));
        assertSame(canonical, catalog.find("finance.receivable_collection.list"));
        assertSame(canonical, catalog.find("FINANCE_RECEIVABLE_COLLECTION"));
        assertEquals("finance.receivable_collection_list",
                catalog.canonicalToolCode("finance.receivable_collection.list"));
        assertEquals("finance.enterprise_kpi", catalog.canonicalToolCode("finance_enterprise_kpi"));
        assertEquals("finance.enterprise_kpi", catalog.canonicalToolCode("finance.enterprise.kpi"));
        assertEquals("finance.supplier_payable_summary",
                catalog.canonicalToolCode("finance_supplier_payable_summary"));
        assertEquals("finance.supplier_payable_summary",
                catalog.canonicalToolCode("finance.supplier.payable.summary"));
    }

    @Test
    void financeCoreToolsRespectGateAndAllMultiSingleSelection() {
        AgentToolCatalog catalog = new AgentToolCatalog();
        AgentRuntimeRecords.Workspace disabled = workspace("ALL", Collections.emptyList(), false);
        assertFalse(catalog.modelDefinitions(disabled).stream()
                .anyMatch(item -> functionName(item).startsWith("finance_enterprise")
                        || functionName(item).startsWith("finance_supplier")));

        for (AgentRuntimeRecords.Workspace workspace : java.util.Arrays.asList(
                workspace("ALL", Collections.emptyList(), true),
                workspace("EXPLICIT", Collections.singletonList("p-1"), true),
                workspace("EXPLICIT", java.util.Arrays.asList("p-1", "p-2"), true))) {
            assertEquals("finance.enterprise_kpi",
                    catalog.find(AgentIntentType.FINANCE_ENTERPRISE_KPI, workspace).getToolCode());
            assertEquals("finance.supplier_payable_summary",
                    catalog.find(AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY, workspace).getToolCode());
        }
        AgentRuntimeRecords.Workspace single = workspace(
                "EXPLICIT", Collections.singletonList("p-1"), true);
        assertTrue(catalog.modelDefinitions(single).stream()
                .anyMatch(item -> "finance_enterprise_kpi".equals(functionName(item))));
    }

    @Test
    void enterpriseKnowledgeBoundaryDoesNotExposeUnrelatedBusinessTools() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setDomain("ENTERPRISE_KNOWLEDGE");
        frame.setUserGoal("查询公司上传的合同模板");

        List<Map<String, Object>> definitions = new AgentToolCatalog().modelDefinitions(
                new AgentRuntimeRecords.Workspace(), frame, new AgentKnowledgeContext());

        assertTrue(definitions.isEmpty());
    }

    @Test
    void unsupportedBusinessFrameExposesZeroWeakTools() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setDomain("FINANCE");
        frame.setCoverage(AgentTaskCoverage.UNSUPPORTED_BUSINESS);
        frame.setRequiresBusinessData(true);
        frame.setUserGoal("公司应收应付和利润分别是多少？");

        List<Map<String, Object>> definitions = new AgentToolCatalog().modelDefinitions(
                workspace("ALL", Collections.emptyList(), true), frame, new AgentKnowledgeContext());

        assertTrue(definitions.isEmpty());
    }

    @Test
    void contractStatusIntentMapsToTheSameAnyScopeToolForAllAndSingle() {
        AgentToolCatalog catalog = new AgentToolCatalog();
        AgentRuntimeRecords.Workspace all = new AgentRuntimeRecords.Workspace();
        all.setScopeType("TENANT");
        all.setSelectionMode("ALL");
        all.setProjectIds(Collections.emptyList());
        AgentRuntimeRecords.Workspace single = new AgentRuntimeRecords.Workspace();
        single.setScopeType("PROJECT");
        single.setSelectionMode("EXPLICIT");
        single.setProjectId("project-1");
        single.setProjectIds(Collections.singletonList("project-1"));

        AgentToolDescriptor allDescriptor = catalog.find(AgentIntentType.PROJECT_CONTRACT_STATUS, all);
        AgentToolDescriptor singleDescriptor = catalog.find(AgentIntentType.PROJECT_CONTRACT_STATUS, single);

        assertEquals("project.contract_status", allDescriptor.getToolCode());
        assertSame(allDescriptor, singleDescriptor);
    }

    @Test
    void ownerActionCenterIsAvailableForEveryScopeWithoutFinanceGate() {
        AgentToolCatalog catalog = new AgentToolCatalog();
        for (AgentRuntimeRecords.Workspace workspace : java.util.Arrays.asList(
                workspace("ALL", Collections.emptyList(), false),
                workspace("EXPLICIT", Collections.singletonList("p-1"), false),
                workspace("EXPLICIT", java.util.Arrays.asList("p-1", "p-2"), true))) {
            AgentToolDescriptor descriptor = catalog.find(AgentIntentType.RISK_OWNER_ACTION_CENTER, workspace);
            assertEquals("risk.owner_action_center", descriptor.getToolCode());
            assertTrue(catalog.modelDefinitions(workspace).stream()
                    .anyMatch(item -> "risk_owner_action_center".equals(functionName(item))));
        }
        assertEquals("risk.owner_action_center", catalog.canonicalToolCode("risk.owner.action.center"));
    }

    @SuppressWarnings("unchecked")
    private String functionName(Map<String, Object> definition) {
        return String.valueOf(((Map<String, Object>) definition.get("function")).get("name"));
    }

    private AgentRuntimeRecords.Workspace workspace(String selectionMode,
                                                    List<String> projectIds,
                                                    boolean financeEnabled) {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode(selectionMode);
        workspace.setProjectIds(projectIds);
        workspace.setProjectId(projectIds.isEmpty() ? null : projectIds.get(0));
        workspace.setFinanceEnabled(financeEnabled);
        return workspace;
    }
}
