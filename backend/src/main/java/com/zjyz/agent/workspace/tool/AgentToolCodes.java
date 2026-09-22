package com.zjyz.agent.workspace.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Canonical tool identifiers shared by task framing, registry, evidence and guards. */
public final class AgentToolCodes {
    public static final String PROJECT_LIST = "project.list";
    public static final String PROJECT_RECONCILIATION_DUE = "project.reconciliation_due";
    public static final String PROJECT_CONTRACT_STATUS = "project.contract_status";
    public static final String PROJECT_MATERIAL_OCCUPANCY = "project.material_occupancy";
    public static final String MATERIAL_TRANSACTION_AGGREGATE = "material.transaction_aggregate";
    public static final String PROJECT_ACTIVITY = "project.activity";
    public static final String PROJECT_SUMMARY = "project.get_summary";
    public static final String INVENTORY_SUMMARY = "inventory.get_summary";
    public static final String INVENTORY_OPERATIONS_SUMMARY = "inventory.operations_summary";
    public static final String INVENTORY_LEDGER_TRACE = "inventory.ledger_trace";
    public static final String DOCUMENT_SEARCH = "document.search";
    public static final String DOCUMENT_AUDIT_LIST = "document.audit_list";
    public static final String MATERIAL_ESTIMATE = "material.estimate";
    public static final String MATERIAL_LIFECYCLE_ANALYTICS = "material.lifecycle_analytics";
    public static final String CONTRACT_COMMERCIAL_ANALYTICS = "contract.commercial_analytics";
    public static final String HELP_SEARCH = "help.search";
    public static final String FINANCE_RECEIVABLE_COLLECTION_LIST = "finance.receivable_collection_list";
    public static final String FINANCE_ENTERPRISE_KPI = "finance.enterprise_kpi";
    public static final String FINANCE_SUPPLIER_PAYABLE_SUMMARY = "finance.supplier_payable_summary";
    public static final String RISK_OWNER_ACTION_CENTER = "risk.owner_action_center";
    private static final Map<String, String> CANONICAL_CODES;

    static {
        Map<String, String> values = new LinkedHashMap<>();
        register(values, "project_list", PROJECT_LIST);
        register(values, "project_reconciliation_due", PROJECT_RECONCILIATION_DUE);
        register(values, "project_contract_status", PROJECT_CONTRACT_STATUS);
        register(values, "project_material_occupancy", PROJECT_MATERIAL_OCCUPANCY);
        register(values, "material_transaction_aggregate", MATERIAL_TRANSACTION_AGGREGATE);
        register(values, "project_activity", PROJECT_ACTIVITY);
        register(values, "project_get_summary", PROJECT_SUMMARY);
        register(values, "inventory_get_summary", INVENTORY_SUMMARY);
        register(values, "inventory_operations_summary", INVENTORY_OPERATIONS_SUMMARY);
        register(values, "inventory_ledger_trace", INVENTORY_LEDGER_TRACE);
        register(values, "document_search", DOCUMENT_SEARCH);
        register(values, "document_audit_list", DOCUMENT_AUDIT_LIST);
        register(values, "material_estimate", MATERIAL_ESTIMATE);
        register(values, "material_lifecycle_analytics", MATERIAL_LIFECYCLE_ANALYTICS);
        register(values, "contract_commercial_analytics", CONTRACT_COMMERCIAL_ANALYTICS);
        register(values, "help_search", HELP_SEARCH);
        register(values, "finance_receivable_collection_list", FINANCE_RECEIVABLE_COLLECTION_LIST);
        values.put(normalize("finance.receivable_collection.list"), FINANCE_RECEIVABLE_COLLECTION_LIST);
        register(values, "finance_enterprise_kpi", FINANCE_ENTERPRISE_KPI);
        values.put(normalize("finance.enterprise.kpi"), FINANCE_ENTERPRISE_KPI);
        register(values, "finance_supplier_payable_summary", FINANCE_SUPPLIER_PAYABLE_SUMMARY);
        values.put(normalize("finance.supplier.payable.summary"), FINANCE_SUPPLIER_PAYABLE_SUMMARY);
        register(values, "risk_owner_action_center", RISK_OWNER_ACTION_CENTER);
        values.put(normalize("risk.owner.action.center"), RISK_OWNER_ACTION_CENTER);
        for (AgentBusinessCapabilities.Definition capability : AgentBusinessCapabilities.definitions()) {
            register(values, capability.modelName(), capability.code);
        }
        CANONICAL_CODES = Collections.unmodifiableMap(values);
    }

    private AgentToolCodes() {
    }

    /** Returns a canonical code for known model/legacy aliases; unknown values remain distinct. */
    public static String canonicalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return CANONICAL_CODES.getOrDefault(normalize(trimmed), trimmed);
    }

    private static void register(Map<String, String> values, String modelName, String canonicalCode) {
        values.put(normalize(modelName), canonicalCode);
        values.put(normalize(canonicalCode), canonicalCode);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
