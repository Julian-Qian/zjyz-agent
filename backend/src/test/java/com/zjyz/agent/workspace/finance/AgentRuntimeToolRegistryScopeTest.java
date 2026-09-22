package com.zjyz.agent.workspace.finance;

import com.zjyz.agent.orch.AgentSlotExtractor;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.orch.AgentSkillHandler;
import com.zjyz.agent.orch.AgentIntentType;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.service.AgentComputeSandbox;
import com.zjyz.agent.workspace.tool.AgentToolCatalog;
import com.zjyz.agent.workspace.tool.AgentRuntimeToolRegistry;
import com.zjyz.agent.workspace.tool.ProjectCatalogSkill;
import com.zjyz.agent.workspace.tool.ProjectReconciliationDueSkill;
import com.zjyz.agent.workspace.tool.ProjectContractStatusSkill;
import com.zjyz.agent.workspace.tool.ProjectMaterialOccupancySkill;
import com.zjyz.agent.workspace.tool.MaterialTransactionAggregateSkill;
import com.zjyz.agent.workspace.tool.ProjectActivitySkill;
import com.zjyz.agent.workspace.tool.InventoryOperationsSkill;
import com.zjyz.agent.workspace.tool.InventoryLedgerTraceSkill;
import com.zjyz.agent.workspace.tool.DocumentAuditSkill;
import com.zjyz.agent.workspace.tool.MaterialLifecycleSkill;
import com.zjyz.agent.workspace.tool.ContractCommercialSkill;
import com.zjyz.agent.workspace.risk.OwnerActionCenterSkill;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.service.ProjectReportService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class AgentRuntimeToolRegistryScopeTest {

    @Test
    void allProjectSelectionReceivesScopedAndFinanceTools() {
        AgentRuntimeToolRegistry registry = registry();
        AgentRuntimeRecords.Workspace tenant = workspace("TENANT", null, "ALL", true);

        List<Map<String, Object>> definitions = registry.modelDefinitions(tenant);

        assertTrue(definitions.stream().anyMatch(definition ->
                "finance_receivable_collection_list".equals(functionName(definition))));
        assertTrue(definitions.stream().anyMatch(definition ->
                "project_list".equals(functionName(definition))));
    }

    @Test
    void projectWorkspaceDoesNotReceiveOrExecuteTenantFinanceTool() {
        AgentRuntimeToolRegistry registry = registry();
        AgentRuntimeRecords.Workspace project = workspace("PROJECT", "project-1", "EXPLICIT", false);

        List<Map<String, Object>> definitions = registry.modelDefinitions(project);

        assertTrue(definitions.stream().noneMatch(definition ->
                "finance_receivable_collection_list".equals(functionName(definition))));
        assertFalse(definitions.isEmpty());
        MyBizException error = assertThrows(MyBizException.class, () -> registry.execute(
                "finance_receivable_collection_list", "查询欠款", "{}", project));
        assertEquals("AGT403", error.getErrorCode());
    }

    @Test
    void registryRejectsOutOfScopeStructuredResultBeforeItBecomesEvidence() {
        ProjectCatalogSkill projectCatalog = mock(ProjectCatalogSkill.class);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("projectId", "project-2");
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("items", Collections.singletonList(item));
        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setCards(Collections.singletonList(card));
        when(projectCatalog.execute(any(), any(), any())).thenReturn(execution);
        AgentRuntimeToolRegistry registry = registry(projectCatalog);
        AgentRuntimeRecords.Workspace project = workspace("TENANT", "project-1", "EXPLICIT", false);

        MyBizException error = assertThrows(MyBizException.class, () -> registry.execute(
                "project.list", "查询项目", "{}", project));

        assertEquals("AGT_SCOPE_VIOLATION", error.getErrorCode());
    }

    @Test
    void registryWritesCanonicalToolCodeAndFrozenScopeIntoEvidence() {
        ProjectCatalogSkill projectCatalog = mock(ProjectCatalogSkill.class);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("projectId", "project-1");
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("items", Collections.singletonList(item));
        AgentSkillExecution raw = new AgentSkillExecution();
        raw.setCards(Collections.singletonList(card));
        when(projectCatalog.execute(any(), any(), any())).thenReturn(raw);
        AgentRuntimeToolRegistry registry = registry(projectCatalog);
        AgentRuntimeRecords.Workspace project = workspace("TENANT", "project-1", "EXPLICIT", false);

        AgentSkillExecution result = registry.execute("project.list", "查询项目", "{}", project);

        assertEquals("project.list", result.getEvidence().getToolCode());
        assertEquals("EXPLICIT", result.getEvidence().getSelectionMode());
        assertEquals(Collections.singletonList("project-1"), result.getEvidence().getProjectIds());
    }

    @Test
    void helpToolAnswerMayContainProjectIdExampleWithoutDisablingStructuredScopeChecks() {
        AgentSkillHandler help = mock(AgentSkillHandler.class);
        AgentSkillExecution helpResult = new AgentSkillExecution();
        helpResult.setAnswer("示例：projectId=project-2");
        when(help.supportedIntent()).thenReturn(AgentIntentType.HELP_KNOWLEDGE);
        when(help.execute(any(), any(), any())).thenReturn(helpResult);
        AgentRuntimeToolRegistry registry = registry(mock(ProjectCatalogSkill.class),
                Collections.singletonList(help), new AgentSlotExtractor());

        AgentSkillExecution result = registry.execute("help_search", "项目怎么操作", "{}",
                workspace("TENANT", "project-1", "EXPLICIT", false));

        assertEquals("示例：projectId=project-2", result.getAnswer());
        assertEquals("help.search", result.getEvidence().getToolCode());
    }

    @Test
    void degradedSingleProjectFinanceIntentUsesRegisteredEnterpriseToolAndHonorsGate() {
        EnterpriseKpiSkill enterprise = mock(EnterpriseKpiSkill.class);
        AgentSkillExecution raw = new AgentSkillExecution();
        raw.setAnswer("当前项目应收、已收和未清台账结果");
        when(enterprise.execute(any(), any(), any())).thenReturn(raw);
        AgentRuntimeToolRegistry registry = registryWithEnterprise(enterprise);
        AgentRuntimeRecords.Workspace enabled = workspace("PROJECT", "project-1", "EXPLICIT", true);

        AgentSkillExecution result = registry.execute(
                AgentIntentType.FINANCE_ENTERPRISE_KPI,
                "这个项目现在应收、已收和未清多少？",
                enabled);

        assertEquals("当前项目应收、已收和未清台账结果", result.getAnswer());
        assertEquals("finance.enterprise_kpi", result.getEvidence().getToolCode());
        assertEquals("EXPLICIT", result.getEvidence().getSelectionMode());
        assertEquals(Collections.singletonList("project-1"), result.getEvidence().getProjectIds());
        verify(enterprise).execute(any(), any(), any());

        MyBizException disabled = assertThrows(MyBizException.class, () -> registry.execute(
                AgentIntentType.FINANCE_ENTERPRISE_KPI,
                "这个项目现在应收、已收和未清多少？",
                workspace("PROJECT", "project-1", "EXPLICIT", false)));
        assertEquals("AGT403", disabled.getErrorCode());
    }

    @Test
    void supplierFinanceToolWritesCanonicalCodeAndFrozenExplicitScope() {
        SupplierPayableSummarySkill supplier = mock(SupplierPayableSummarySkill.class);
        AgentSkillExecution raw = new AgentSkillExecution();
        raw.setAnswer("租入项目供应商应付结果");
        when(supplier.execute(any(), any(), any())).thenReturn(raw);
        AgentRuntimeToolRegistry registry = registryWithSupplier(supplier);
        AgentRuntimeRecords.Workspace workspace = workspace("PROJECT", "project-1", "EXPLICIT", true);

        AgentSkillExecution result = registry.execute(
                "finance_supplier_payable_summary", "供应商应付多少", "{}", workspace);

        assertEquals("finance.supplier_payable_summary", result.getEvidence().getToolCode());
        assertEquals("EXPLICIT", result.getEvidence().getSelectionMode());
        assertEquals(Collections.singletonList("project-1"), result.getEvidence().getProjectIds());
        verify(supplier).execute(any(), any(), any());
    }

    @Test
    void ownerActionCenterExecutesInSingleScopeAndWritesCanonicalFrozenEvidence() {
        OwnerActionCenterSkill owner = mock(OwnerActionCenterSkill.class);
        AgentSkillExecution raw = new AgentSkillExecution();
        raw.setAnswer("老板行动中心结果");
        when(owner.execute(any(), any(), any())).thenReturn(raw);
        AgentRuntimeToolRegistry registry = registryWithOwner(owner);
        AgentRuntimeRecords.Workspace workspace = workspace("PROJECT", "project-1", "EXPLICIT", false);

        AgentSkillExecution result = registry.execute(
                "risk_owner_action_center", "这个项目有哪些风险需要处理？", "{}", workspace);

        assertEquals("risk.owner_action_center", result.getEvidence().getToolCode());
        assertEquals("EXPLICIT", result.getEvidence().getSelectionMode());
        assertEquals(Collections.singletonList("project-1"), result.getEvidence().getProjectIds());
        verify(owner).execute(any(), any(), any());
    }

    @Test
    void newBusinessQueriesAlwaysUseRollbackSandbox() {
        AgentRuntimeToolRegistry registry = registry();
        com.zjyz.agent.workspace.tool.AgentBusinessQueryService business = mock(com.zjyz.agent.workspace.tool.AgentBusinessQueryService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(registry,"businessQueryService",business);
        AgentComputeSandbox sandbox=(AgentComputeSandbox)org.springframework.test.util.ReflectionTestUtils.getField(registry,"computeSandbox");
        when(sandbox.executeWithoutCommit(any())).thenAnswer(i -> ((java.util.function.Supplier<?>)i.getArgument(0)).get());
        AgentSkillExecution execution=new AgentSkillExecution();execution.setAnswer("商城结果");
        AgentRuntimeRecords.Workspace w=workspace("TENANT",null,"EXPLICIT",false);w.setProjectIds(Collections.emptyList());
        when(business.execute("market.search","{}",w)).thenReturn(execution);
        assertEquals(execution,registry.execute("market_search","找钢管","{}",w));
        verify(sandbox).executeWithoutCommit(any());
        verify(business).execute("market.search","{}",w);
    }

    private AgentRuntimeToolRegistry registry() {
        return registry(mock(ProjectCatalogSkill.class));
    }

    private AgentRuntimeToolRegistry registry(ProjectCatalogSkill projectCatalogSkill) {
        return registry(projectCatalogSkill, Collections.emptyList(), mock(AgentSlotExtractor.class));
    }

    private AgentRuntimeToolRegistry registry(ProjectCatalogSkill projectCatalogSkill,
                                              List<AgentSkillHandler> handlers,
                                              AgentSlotExtractor slotExtractor) {
        return new AgentRuntimeToolRegistry(
                handlers,
                new AgentToolCatalog(),
                slotExtractor,
                mock(ProjectMapper.class),
                mock(AgentComputeSandbox.class),
                mock(ProjectReportService.class),
                mock(ReceivableCollectionSkill.class),
                mock(EnterpriseKpiSkill.class),
                mock(SupplierPayableSummarySkill.class),
                projectCatalogSkill,
                mock(ProjectReconciliationDueSkill.class),
                mock(ProjectContractStatusSkill.class),
                mock(ProjectMaterialOccupancySkill.class),
                mock(MaterialTransactionAggregateSkill.class),
                mock(ProjectActivitySkill.class),
                mock(OwnerActionCenterSkill.class),
                mock(InventoryOperationsSkill.class),
                mock(InventoryLedgerTraceSkill.class),
                mock(DocumentAuditSkill.class),
                mock(MaterialLifecycleSkill.class),
                mock(ContractCommercialSkill.class));
    }

    private AgentRuntimeToolRegistry registryWithEnterprise(EnterpriseKpiSkill enterpriseKpiSkill) {
        return new AgentRuntimeToolRegistry(
                Collections.emptyList(),
                new AgentToolCatalog(),
                mock(AgentSlotExtractor.class),
                mock(ProjectMapper.class),
                mock(AgentComputeSandbox.class),
                mock(ProjectReportService.class),
                mock(ReceivableCollectionSkill.class),
                enterpriseKpiSkill,
                mock(SupplierPayableSummarySkill.class),
                mock(ProjectCatalogSkill.class),
                mock(ProjectReconciliationDueSkill.class),
                mock(ProjectContractStatusSkill.class),
                mock(ProjectMaterialOccupancySkill.class),
                mock(MaterialTransactionAggregateSkill.class),
                mock(ProjectActivitySkill.class),
                mock(OwnerActionCenterSkill.class),
                mock(InventoryOperationsSkill.class),
                mock(InventoryLedgerTraceSkill.class),
                mock(DocumentAuditSkill.class),
                mock(MaterialLifecycleSkill.class),
                mock(ContractCommercialSkill.class));
    }

    private AgentRuntimeToolRegistry registryWithSupplier(SupplierPayableSummarySkill supplierPayableSummarySkill) {
        return new AgentRuntimeToolRegistry(
                Collections.emptyList(),
                new AgentToolCatalog(),
                mock(AgentSlotExtractor.class),
                mock(ProjectMapper.class),
                mock(AgentComputeSandbox.class),
                mock(ProjectReportService.class),
                mock(ReceivableCollectionSkill.class),
                mock(EnterpriseKpiSkill.class),
                supplierPayableSummarySkill,
                mock(ProjectCatalogSkill.class),
                mock(ProjectReconciliationDueSkill.class),
                mock(ProjectContractStatusSkill.class),
                mock(ProjectMaterialOccupancySkill.class),
                mock(MaterialTransactionAggregateSkill.class),
                mock(ProjectActivitySkill.class),
                mock(OwnerActionCenterSkill.class),
                mock(InventoryOperationsSkill.class),
                mock(InventoryLedgerTraceSkill.class),
                mock(DocumentAuditSkill.class),
                mock(MaterialLifecycleSkill.class),
                mock(ContractCommercialSkill.class));
    }

    private AgentRuntimeToolRegistry registryWithOwner(OwnerActionCenterSkill ownerActionCenterSkill) {
        return new AgentRuntimeToolRegistry(
                Collections.emptyList(),
                new AgentToolCatalog(),
                mock(AgentSlotExtractor.class),
                mock(ProjectMapper.class),
                mock(AgentComputeSandbox.class),
                mock(ProjectReportService.class),
                mock(ReceivableCollectionSkill.class),
                mock(EnterpriseKpiSkill.class),
                mock(SupplierPayableSummarySkill.class),
                mock(ProjectCatalogSkill.class),
                mock(ProjectReconciliationDueSkill.class),
                mock(ProjectContractStatusSkill.class),
                mock(ProjectMaterialOccupancySkill.class),
                mock(MaterialTransactionAggregateSkill.class),
                mock(ProjectActivitySkill.class),
                ownerActionCenterSkill,
                mock(InventoryOperationsSkill.class),
                mock(InventoryLedgerTraceSkill.class),
                mock(DocumentAuditSkill.class),
                mock(MaterialLifecycleSkill.class),
                mock(ContractCommercialSkill.class));
    }

    @SuppressWarnings("unchecked")
    private String functionName(Map<String, Object> definition) {
        return String.valueOf(((Map<String, Object>) definition.get("function")).get("name"));
    }

    private AgentRuntimeRecords.Workspace workspace(String scopeType,
                                                    String projectId,
                                                    String selectionMode,
                                                    boolean financeEnabled) {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setScopeType(scopeType);
        workspace.setProjectId(projectId);
        workspace.setSelectionMode(selectionMode);
        workspace.setProjectIds(projectId == null ? Collections.emptyList() : Collections.singletonList(projectId));
        workspace.setFinanceEnabled(financeEnabled);
        workspace.setCid("cid-1");
        return workspace;
    }
}
