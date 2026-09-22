package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.service.AgentWorkspaceRuntimeService;
import com.zjyz.agent.workspace.tool.AgentToolCatalog;
import com.zjyz.agent.workspace.tool.AgentBusinessCapabilities;
import com.zjyz.agent.workspace.tool.AgentToolDescriptor;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import com.zjyz.common.security.AuthContext;
import com.zjyz.common.util.CommonUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/** Single user-facing source of truth for the capabilities visible to a V2 turn. */
@Service
public class AgentV2CapabilityManifestService {
    private final AgentWorkspaceRuntimeService legacyAccessService;
    private final AgentToolCatalog toolCatalog;

    @Value("${agent.attachment.enabled:false}")
    private boolean attachmentEnabled;

    @Value("${agent.v2.enabled:${AGENT_V2_ENABLED:false}}")
    private boolean v2Enabled;
    @Value("${agent.v2.allowedCids:${AGENT_V2_ALLOWED_CIDS:}}")
    private String allowedCids;
    @Value("${agent.v2.allowedUids:${AGENT_V2_ALLOWED_UIDS:}}")
    private String allowedUids;

    public AgentV2CapabilityManifestService(AgentWorkspaceRuntimeService legacyAccessService,
                                            AgentToolCatalog toolCatalog) {
        this.legacyAccessService = legacyAccessService;
        this.toolCatalog = toolCatalog;
    }

    public Map<String, Object> manifest(AgentRuntimeRecords.Workspace scopedWorkspace) {
        Map<String, Object> access = legacyAccessService.capabilities(null);
        boolean legacyEnabled = Boolean.TRUE.equals(access.get("enabled"));
        boolean cidAllowed = !configured(allowedCids) || allowed(allowedCids, currentCid());
        boolean uidAllowed = !configured(allowedUids) || allowed(allowedUids, AuthContext.getUid());
        boolean v2Allowed = v2Enabled && cidAllowed && uidAllowed;
        boolean runtimeEnabled = legacyEnabled && v2Allowed;
        boolean financeEnabled = Boolean.TRUE.equals(access.get("tenantFinanceEnabled"));

        List<AgentV2Models.Capability> capabilities = new ArrayList<>();
        for (AgentToolDescriptor descriptor : toolCatalog.descriptors()) {
            boolean scopeSupported = scopedWorkspace == null
                    || toolCatalog.supportsSelection(descriptor, scopedWorkspace);
            boolean financeAllowed = !descriptor.getToolCode().startsWith("finance.") || financeEnabled;
            AgentV2Models.Capability capability = new AgentV2Models.Capability();
            capability.setCode(descriptor.getToolCode());
            capability.setName(displayName(descriptor.getToolCode()));
            capability.setDescription(descriptor.getDescription());
            capability.setRiskLevel(descriptor.getRiskLevel());
            capability.setRequiredScope(descriptor.getSelectionSupport());
            capability.setScopeBehavior(descriptor.getScopeBehavior());
            AgentBusinessCapabilities.Definition business = AgentBusinessCapabilities.find(descriptor.getToolCode());
            capability.setDataScope(business != null ? business.scope
                    : AgentToolDescriptor.SCOPE_RESPECTS_SELECTION.equals(descriptor.getScopeBehavior())
                    ? "PROJECT_SELECTION" : AgentToolDescriptor.SCOPE_NOT_APPLICABLE.equals(descriptor.getScopeBehavior())
                    ? "NOT_APPLICABLE" : "TENANT_INVENTORY");
            if (business != null) capability.setRequiredInputs(business.required);

            boolean safeRisk = "READ".equals(descriptor.getRiskLevel()) || "COMPUTE".equals(descriptor.getRiskLevel());
            boolean available = runtimeEnabled && scopeSupported && financeAllowed && safeRisk;
            capability.setEnabled(available);
            capability.setAvailable(available);
            capability.setUnavailableReason(unavailableReason(descriptor, scopedWorkspace,
                    legacyEnabled, v2Allowed, scopeSupported, financeAllowed, safeRisk));
            capability.setReadOnly(true);
            capability.setExamples(examples(descriptor.getToolCode()));
            capabilities.add(capability);
        }

        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("name", "小云");
        assistant.put("shape", "建材租赁经营副驾");
        assistant.put("shapeCode", "RENTAL_OPERATIONS_COPILOT");
        assistant.put("pillars", Arrays.asList("问", "办", "盯"));

        Map<String, Object> conversationFeatures = new LinkedHashMap<>();
        conversationFeatures.put("documentReview", attachmentEnabled && runtimeEnabled);
        conversationFeatures.put("evidenceAnalysis", true);
        conversationFeatures.put("multiTurn", true);
        conversationFeatures.put("followUp", true);
        conversationFeatures.put("correction", true);
        conversationFeatures.put("clarification", true);
        conversationFeatures.put("realTimeEvents", true);
        conversationFeatures.put("taskRunSeparated", true);

        Map<String, Object> scope = new LinkedHashMap<>();
        if (scopedWorkspace == null) {
            scope.put("resolved", false);
        } else {
            scope.put("resolved", true);
            scope.put("selectionMode", scopedWorkspace.getSelectionMode());
            scope.put("projectIds", scopedWorkspace.getProjectIds());
            scope.put("projectCount", scopedWorkspace.getProjectIds() == null
                    ? 0 : scopedWorkspace.getProjectIds().size());
            scope.put("frozen", true);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("manifestVersion", "2026-09-19.1");
        result.put("metricBoundaries", Arrays.asList(
                "材料流水排行仅支持数量和单据次数，不支持材料收入、回款或利润排行。",
                "赚钱口径不明确时应区分租金收入和扣除成本后的利润；不得用数量替代金额。"));
        result.put("generatedAt", LocalDateTime.now());
        result.put("runtimeVersion", AgentV2Models.RUNTIME_VERSION);
        result.put("mode", runtimeEnabled ? "READ_ONLY" : "DISABLED");
        result.put("enabled", runtimeEnabled);
        result.put("v2Enabled", v2Allowed);
        result.put("writeEnabled", false);
        result.put("assistant", assistant);
        result.put("scope", scope);
        result.put("capabilities", capabilities);
        result.put("conversationFeatures", conversationFeatures);
        result.put("allowedRiskLevels", Arrays.asList("READ", "COMPUTE"));
        result.put("legacyAccess", access);
        return result;
    }

    private String unavailableReason(AgentToolDescriptor descriptor,
                                     AgentRuntimeRecords.Workspace workspace,
                                     boolean legacyEnabled,
                                     boolean v2Allowed,
                                     boolean scopeSupported,
                                     boolean financeAllowed,
                                     boolean safeRisk) {
        if (!v2Allowed) {
            return "V2_DISABLED";
        }
        if (!legacyEnabled) {
            return "RUNTIME_DISABLED";
        }
        if (!scopeSupported) {
            if (workspace != null && "ALL".equals(descriptor.getSelectionSupport())
                    && "EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())) {
                return "FROZEN_SCOPE_NOT_SUPPORTED";
            }
            return "SCOPE_NOT_SUPPORTED";
        }
        if (!financeAllowed) {
            return "FINANCE_ENTITLEMENT_REQUIRED";
        }
        if (!safeRisk) {
            return "READ_ONLY_PHASE";
        }
        return null;
    }

    public void requireEnabled() {
        Map<String, Object> manifest = manifest(null);
        if (!Boolean.TRUE.equals(manifest.get("v2Enabled"))) {
            throw new com.zjyz.common.exception.MyBizException("小云暂未开放", "AGT_V2_DISABLED");
        }
    }

    private boolean allowed(String configured, String value) {
        if (configured == null || configured.trim().isEmpty()) {
            return true;
        }
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        for (String item : configured.split(",")) {
            if (value.trim().equals(item.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean configured(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String currentCid() {
        String cid = AuthContext.getCid();
        return cid == null || cid.trim().isEmpty() ? CommonUtil.getCid() : cid;
    }

    @SuppressWarnings("unchecked")
    public boolean financeEnabled(Map<String, Object> manifest) {
        if (manifest == null || !(manifest.get("legacyAccess") instanceof Map)) {
            return false;
        }
        return Boolean.TRUE.equals(((Map<String, Object>) manifest.get("legacyAccess"))
                .get("tenantFinanceEnabled"));
    }

    private String displayName(String code) {
        if (AgentBusinessCapabilities.find(code) != null) return AgentBusinessCapabilities.find(code).title;
        Map<String, String> names = new LinkedHashMap<>();
        names.put("project.list", "项目清单");
        names.put("project.reconciliation_due", "未对账检查");
        names.put("project.contract_status", "合同状态");
        names.put("project.material_occupancy", "材料占用与未归还");
        names.put("material.transaction_aggregate", "材料流水排行");
        names.put("project.activity", "项目业务活跃度");
        names.put("risk.owner_action_center", "老板行动中心");
        names.put("project.get_summary", "项目经营快照");
        names.put("inventory.get_summary", "库存概况");
        names.put("inventory.operations_summary", "库存异常运营");
        names.put("inventory.ledger_trace", "库存台账追溯");
        names.put("document.search", "业务单据检索");
        names.put("document.audit_list", "单据审核清单");
        names.put("material.estimate", "材料需求估算");
        names.put("material.lifecycle_analytics", "材料生命周期分析");
        names.put("contract.commercial_analytics", "合同商业分析");
        names.put("help.search", "系统帮助");
        names.put("finance.receivable_collection_list", "应收催缴清单");
        names.put("finance.enterprise_kpi", "企业往来账概览");
        names.put("finance.supplier_payable_summary", "供应商应付概览");
        return names.getOrDefault(code, code);
    }

    private List<String> examples(String code) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("project.list", Arrays.asList("目前小何在负责哪些项目", "列出今年新增的租出项目"));
        values.put("project.reconciliation_due", Collections.singletonList("哪些项目已经很久没有对账"));
        values.put("project.contract_status", Collections.singletonList("这个月有哪些合同到期"));
        values.put("project.material_occupancy", Collections.singletonList("哪些项目还有材料没还"));
        values.put("material.transaction_aggregate", Collections.singletonList("上个月租出最多的材料是什么"));
        values.put("project.activity", Collections.singletonList("找出最近没有业务动作的项目"));
        values.put("risk.owner_action_center", Collections.singletonList("今天最需要我处理什么"));
        values.put("project.get_summary", Collections.singletonList("这个项目现在经营得怎么样"));
        values.put("inventory.get_summary", Collections.singletonList("当前库存和低库存情况"));
        values.put("inventory.operations_summary", Collections.singletonList("哪些材料出现负库存或长期无流水"));
        values.put("inventory.ledger_trace", Collections.singletonList("追溯钢管48-3的库存台账流水"));
        values.put("document.search", Collections.singletonList("查找当前项目最近的归还单"));
        values.put("document.audit_list", Collections.singletonList("有哪些单据还没复核"));
        values.put("material.estimate", Collections.singletonList("按建筑参数估算材料需求"));
        values.put("material.lifecycle_analytics", Collections.singletonList("哪些材料赔偿率最高或长期只租不还"));
        values.put("contract.commercial_analytics", Collections.singletonList("同一种材料在不同项目的租金差多少"));
        values.put("help.search", Collections.singletonList("如何审核租出单"));
        values.put("finance.receivable_collection_list", Collections.singletonList("生成逾期应收催缴清单"));
        values.put("finance.enterprise_kpi", Collections.singletonList("汇总当前应收应付和实收实付"));
        values.put("finance.supplier_payable_summary", Collections.singletonList("供应商还有多少款没付"));
        return values.getOrDefault(code, Collections.emptyList());
    }
}
