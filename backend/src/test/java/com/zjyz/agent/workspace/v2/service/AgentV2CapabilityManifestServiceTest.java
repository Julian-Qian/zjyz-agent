package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.service.AgentWorkspaceRuntimeService;
import com.zjyz.agent.workspace.tool.AgentToolCatalog;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentV2CapabilityManifestServiceTest {

    @Test
    void frozenAllDoesNotExposeDynamicAllOnlyCollectionTool() {
        AgentWorkspaceRuntimeService legacy = mock(AgentWorkspaceRuntimeService.class);
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("enabled", true);
        access.put("tenantFinanceEnabled", true);
        when(legacy.capabilities(null)).thenReturn(access);
        AgentV2CapabilityManifestService service = new AgentV2CapabilityManifestService(
                legacy, new AgentToolCatalog());
        ReflectionTestUtils.setField(service, "v2Enabled", true);
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("EXPLICIT");
        workspace.setProjectIds(Arrays.asList("p1", "p2"));

        Map<String, Object> manifest = service.manifest(workspace);
        AgentV2Models.Capability collection = capability(manifest, "finance.receivable_collection_list");
        AgentV2Models.Capability enterprise = capability(manifest, "finance.enterprise_kpi");
        AgentV2Models.Capability supplier = capability(manifest, "finance.supplier_payable_summary");

        assertFalse(collection.isAvailable());
        assertEquals("FROZEN_SCOPE_NOT_SUPPORTED", collection.getUnavailableReason());
        assertTrue(enterprise.isAvailable());
        assertTrue(supplier.isAvailable());
        assertEquals("2026-09-19.1", manifest.get("manifestVersion"));
    }

    @Test
    void v2IsDisabledByDefaultUntilDeploymentFlagIsEnabled() {
        AgentWorkspaceRuntimeService legacy = mock(AgentWorkspaceRuntimeService.class);
        Map<String, Object> access = new LinkedHashMap<>();
        access.put("enabled", true);
        when(legacy.capabilities(null)).thenReturn(access);
        AgentV2CapabilityManifestService service = new AgentV2CapabilityManifestService(
                legacy, new AgentToolCatalog());

        Map<String, Object> manifest = service.manifest(null);

        assertEquals("DISABLED", manifest.get("mode"));
        assertEquals(Boolean.FALSE, manifest.get("v2Enabled"));
        assertEquals(Boolean.FALSE, manifest.get("enabled"));
    }

    @Test
    void noProjectsStillExposesMarketInventoryAndMasterDataButNotProjectTools() {
        AgentWorkspaceRuntimeService legacy = mock(AgentWorkspaceRuntimeService.class);
        when(legacy.capabilities(null)).thenReturn(Map.of("enabled",true,"tenantFinanceEnabled",false));
        AgentV2CapabilityManifestService service = new AgentV2CapabilityManifestService(legacy,new AgentToolCatalog());
        ReflectionTestUtils.setField(service,"v2Enabled",true);
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setScopeType("TENANT");workspace.setSelectionMode("EXPLICIT");
        workspace.setProjectIds(java.util.Collections.emptyList());
        Map<String,Object> manifest=service.manifest(workspace);
        assertTrue(capability(manifest,"market.search").isAvailable());
        assertEquals("PUBLIC_MARKET",capability(manifest,"market.search").getDataScope());
        assertTrue(capability(manifest,"inventory.materials").isAvailable());
        assertTrue(capability(manifest,"master.personnel").isAvailable());
        assertFalse(capability(manifest,"project.detail").isAvailable());
        assertFalse(capability(manifest,"finance.customer_payments").isAvailable());
    }

    @SuppressWarnings("unchecked")
    private AgentV2Models.Capability capability(Map<String, Object> manifest, String code) {
        return ((List<AgentV2Models.Capability>) manifest.get("capabilities")).stream()
                .filter(item -> code.equals(item.getCode()))
                .findFirst()
                .orElseThrow(AssertionError::new);
    }
}
