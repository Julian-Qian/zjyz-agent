package com.zjyz.agent.workspace.tool;

import com.zjyz.agent.workspace.context.AgentTaskFrame;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0-2 裁剪修正：minimumRequiredTools 不允许被相关性打分挤出模型可见工具集。 */
class AgentToolCatalogExposureTest {

    private final AgentToolCatalog catalog = new AgentToolCatalog();

    @Test
    void requiredToolAlwaysExposedEvenWithIrrelevantGoal() {
        AgentTaskFrame frame = new AgentTaskFrame();
        // 目标文本与库存领域强相关，与财务应收无词面重合，用于制造打分劣势。
        frame.setDomain("INVENTORY");
        frame.setUserGoal("仓库库存预警和低库存趋势，库存概况，库存明细");
        frame.setMinimumRequiredTools(new ArrayList<>(
                Arrays.asList(AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST)));

        List<String> exposed = exposedCodes(catalog.modelDefinitions(allScopeWorkspace(), frame, null));
        assertTrue(exposed.contains(AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST),
                "必需工具必须无条件入选: " + exposed);
        assertTrue(exposed.size() <= 6, "非必需工具裁剪上限仍为 6: " + exposed);
    }

    @Test
    void multipleRequiredToolsAllExposed() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setDomain("GENERAL");
        frame.setUserGoal("经营总览");
        frame.setMinimumRequiredTools(new ArrayList<>(Arrays.asList(
                AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST,
                AgentToolCodes.PROJECT_RECONCILIATION_DUE,
                AgentToolCodes.MATERIAL_TRANSACTION_AGGREGATE)));

        List<String> exposed = exposedCodes(catalog.modelDefinitions(allScopeWorkspace(), frame, null));
        assertTrue(exposed.containsAll(Arrays.asList(
                AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST,
                AgentToolCodes.PROJECT_RECONCILIATION_DUE,
                AgentToolCodes.MATERIAL_TRANSACTION_AGGREGATE)), "全部必需工具必须入选: " + exposed);
    }

    @Test
    void unsupportedCoverageStillExposesNothing() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setCoverage(com.zjyz.agent.workspace.context.AgentTaskCoverage.UNSUPPORTED_BUSINESS);
        frame.setMinimumRequiredTools(new ArrayList<>());
        assertEquals(0, catalog.modelDefinitions(allScopeWorkspace(), frame, null).size());
    }

    @Test
    void scopeBehaviorIsDeclaredAndSurfacedToModel() {
        assertEquals(AgentToolDescriptor.SCOPE_IGNORES_SELECTION,
                catalog.find("inventory_get_summary").getScopeBehavior());
        assertEquals(AgentToolDescriptor.SCOPE_NOT_APPLICABLE,
                catalog.find("help_search").getScopeBehavior());
        assertEquals(AgentToolDescriptor.SCOPE_RESPECTS_SELECTION,
                catalog.find("project_list").getScopeBehavior());

        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setDomain("INVENTORY");
        frame.setUserGoal("当前库存情况");
        frame.setMinimumRequiredTools(new ArrayList<>(
                Arrays.asList(AgentToolCodes.INVENTORY_SUMMARY)));
        boolean declared = catalog.modelDefinitions(allScopeWorkspace(), frame, null).stream()
                .map(definition -> (Map<String, Object>) definition.get("function"))
                .filter(function -> "inventory_get_summary".equals(function.get("name")))
                .anyMatch(function -> String.valueOf(function.get("description")).contains("忽略当前项目多选"));
        assertTrue(declared, "IGNORES_SELECTION 必须在模型可见描述中显式声明");
    }

    private AgentRuntimeRecords.Workspace allScopeWorkspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setSelectionMode("ALL");
        workspace.setScopeType("TENANT");
        workspace.setProjectIds(new ArrayList<>());
        workspace.setFinanceEnabled(true);
        return workspace;
    }

    @SuppressWarnings("unchecked")
    private List<String> exposedCodes(List<Map<String, Object>> definitions) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> definition : definitions) {
            Map<String, Object> function = (Map<String, Object>) definition.get("function");
            result.add(catalog.canonicalToolCode(String.valueOf(function.get("name"))));
        }
        return result;
    }
}
