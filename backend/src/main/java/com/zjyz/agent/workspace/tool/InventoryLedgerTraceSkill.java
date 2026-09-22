package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.pojo.param.req.QueryInventoryLedgerParam;
import com.zjyz.pojo.param.req.QueryInventoryMaterialsParam;
import com.zjyz.pojo.param.ret.InventoryLedgerItemRet;
import com.zjyz.pojo.param.ret.InventoryLedgerListRet;
import com.zjyz.pojo.param.ret.InventoryMaterialItemRet;
import com.zjyz.pojo.param.ret.InventoryMaterialListRet;
import com.zjyz.service.InventoryService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * D1 库存台账追溯（inventory.ledger_trace）：按材料追溯库存台账流水与前后余额。
 * 企业级材料维度口径（IGNORES_SELECTION）；材料不唯一时返回澄清而不猜测。
 */
@Component
public class InventoryLedgerTraceSkill {
    public static final String TOOL_CODE = "inventory.ledger_trace";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    public InventoryLedgerTraceSkill(InventoryService inventoryService, ObjectMapper objectMapper) {
        this.inventoryService = inventoryService;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson, String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null) {
            throw new MyBizException("工作空间上下文缺失", "AGT400");
        }
        Query query = parseQuery(argumentsJson);
        if (!StringUtils.hasText(query.materialId) && !StringUtils.hasText(query.materialKeyword)) {
            return AgentSkillExecution.clarification("inventory_ledger_trace",
                    "需要追溯哪个材料的库存台账？请提供材料名称或规格关键词。",
                    Collections.singletonList("materialKeyword"), traceEvidence(0, null));
        }
        InventoryMaterialItemRet material = resolveMaterial(query);
        if (material == null) {
            AgentSkillExecution execution = new AgentSkillExecution();
            execution.setIntent("inventory_ledger_trace");
            execution.setConfidence(0.9d);
            execution.setAnswer("按关键词“" + safe(query.materialKeyword) + "”没有找到库存材料，无法追溯台账。"
                    + "请确认材料名称或规格后重试。");
            execution.setEvidence(traceEvidence(0, query));
            return execution;
        }
        if (material.getMaterialId() == null) {
            // resolveMaterial 用 null materialId 表示命中多个且无法唯一确定
            return AgentSkillExecution.clarification("inventory_ledger_trace",
                    "关键词“" + safe(query.materialKeyword) + "”匹配到多个材料，请补充规格以唯一确定："
                            + safe(material.getMaterialName()),
                    Collections.singletonList("materialKeyword"), traceEvidence(0, query));
        }

        QueryInventoryLedgerParam ledgerParam = new QueryInventoryLedgerParam();
        ledgerParam.setMaterialId(material.getMaterialId());
        ledgerParam.setPageNum(1);
        ledgerParam.setPageSize(query.limit);
        ledgerParam.setStartDate(query.startDate);
        ledgerParam.setEndDate(query.endDate);
        ledgerParam.setBehaviorTypes(query.behaviorTypes);
        InventoryLedgerListRet ledger = inventoryService.queryInventoryLedger(ledgerParam);
        long totalCount = ledger == null || ledger.getTotalNum() == null ? 0L : ledger.getTotalNum();
        List<InventoryLedgerItemRet> rows = ledger == null || ledger.getList() == null
                ? Collections.emptyList() : ledger.getList();

        List<Map<String, Object>> items = new ArrayList<>();
        for (InventoryLedgerItemRet row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventTime", row.getEventTime());
            item.put("behaviorName", row.getBehaviorName());
            item.put("quantity", row.getQuantity());
            item.put("inventoryDelta", row.getInventoryDelta());
            item.put("afterInventory", row.getAfterInventory());
            item.put("inventoryUnit", row.getInventoryUnit());
            item.put("projectName", row.getProjectName());
            item.put("documentName", row.getDocumentName());
            item.put("operator", row.getOperator());
            items.add(item);
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "inventory-ledger-trace");
        card.put("schemaVersion", "1.0");
        card.put("metricVersion", "inventory-ledger-v1");
        card.put("materialId", material.getMaterialId());
        card.put("materialName", material.getMaterialName());
        card.put("materialSpecification", material.getMaterialSpecification());
        card.put("inventoryUnit", material.getInventoryUnit());
        card.put("currentInventoryQuantity", material.getInventoryQuantity());
        card.put("startDate", query.startDate);
        card.put("endDate", query.endDate);
        card.put("totalCount", totalCount);
        card.put("displayedCount", items.size());
        card.put("truncated", totalCount > items.size());
        card.put("limit", query.limit);
        card.put("items", items);
        card.put("scopeNote", "库存台账为企业级材料维度，忽略项目多选；数量单位为该材料的库存计数单位。");

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("inventory_ledger_trace");
        execution.setConfidence(0.92d);
        execution.setAnswer(buildAnswer(material, query, totalCount, items.size()));
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(traceEvidence((int) Math.min(totalCount, Integer.MAX_VALUE), query));
        return execution;
    }

    private InventoryMaterialItemRet resolveMaterial(Query query) {
        QueryInventoryMaterialsParam param = new QueryInventoryMaterialsParam();
        param.setPageNum(1);
        param.setPageSize(10);
        param.setKeyword(StringUtils.hasText(query.materialId) ? query.materialId : query.materialKeyword);
        InventoryMaterialListRet result = inventoryService.queryInventoryMaterials(param);
        List<InventoryMaterialItemRet> matches = result == null || result.getList() == null
                ? Collections.emptyList() : result.getList();
        if (StringUtils.hasText(query.materialId)) {
            for (InventoryMaterialItemRet item : matches) {
                if (query.materialId.equals(item.getMaterialId())) {
                    return item;
                }
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        String keyword = safe(query.materialKeyword);
        List<InventoryMaterialItemRet> exact = matches.stream()
                .filter(item -> keyword.equals(safe(item.getMaterialName()))
                        || keyword.equals(safe(item.getMaterialName()) + safe(item.getMaterialSpecification())))
                .collect(Collectors.toList());
        if (exact.size() == 1) {
            return exact.get(0);
        }
        InventoryMaterialItemRet ambiguous = new InventoryMaterialItemRet();
        ambiguous.setMaterialId(null);
        ambiguous.setMaterialName(matches.stream().limit(5)
                .map(item -> safe(item.getMaterialName()) + " " + safe(item.getMaterialSpecification()))
                .collect(Collectors.joining("、")));
        return ambiguous;
    }

    private String buildAnswer(InventoryMaterialItemRet material, Query query, long totalCount, int displayedCount) {
        String period = StringUtils.hasText(query.startDate) || StringUtils.hasText(query.endDate)
                ? "在 " + safe(query.startDate) + "~" + safe(query.endDate) + " 期间" : "";
        String materialLabel = safe(material.getMaterialName())
                + (StringUtils.hasText(material.getMaterialSpecification()) ? " " + material.getMaterialSpecification() : "");
        if (totalCount == 0) {
            return "材料“" + materialLabel + "”" + period + "没有库存台账流水记录。当前库存 "
                    + material.getInventoryQuantity() + " " + safe(material.getInventoryUnit()) + "。";
        }
        return "材料“" + materialLabel + "”" + period + "共有 " + totalCount + " 条库存台账流水"
                + (totalCount > displayedCount ? "，结果卡展示最近 " + displayedCount + " 条" : "")
                + "。当前库存 " + material.getInventoryQuantity() + " " + safe(material.getInventoryUnit())
                + "，每条流水均带变动前后余额，可用于追溯差异来源。";
    }

    private AgentEvidence traceEvidence(int recordCount, Query query) {
        AgentEvidence evidence = new AgentEvidence();
        String range = query == null || (!StringUtils.hasText(query.startDate) && !StringUtils.hasText(query.endDate))
                ? "asOf=" + LocalDate.now()
                : safe(query.startDate) + "~" + safe(query.endDate);
        evidence.setTimeRange(range);
        evidence.setSkills(Collections.singletonList("InventoryLedgerTraceSkill"));
        evidence.setApiList(Arrays.asList("internal:InventoryService.queryInventoryMaterials",
                "internal:InventoryService.queryInventoryLedger"));
        evidence.setRecordCount(recordCount);
        return evidence;
    }

    private Query parseQuery(String argumentsJson) {
        Query query = new Query();
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                query.materialId = trimToNull(root.path("materialId").asText(null));
                query.materialKeyword = trimToNull(root.path("materialKeyword").asText(null));
                query.startDate = trimToNull(root.path("startDate").asText(null));
                query.endDate = trimToNull(root.path("endDate").asText(null));
                query.behaviorTypes = trimToNull(root.path("behaviorTypes").asText(null));
                int limit = root.path("limit").asInt(DEFAULT_LIMIT);
                query.limit = Math.max(1, Math.min(limit, MAX_LIMIT));
            } catch (Exception e) {
                throw new MyBizException("库存台账查询参数格式不正确", "AGT400");
            }
        }
        return query;
    }

    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String safe(String value) { return value == null ? "" : value; }

    private static class Query {
        private String materialId;
        private String materialKeyword;
        private String startDate;
        private String endDate;
        private String behaviorTypes;
        private int limit = DEFAULT_LIMIT;
    }
}
