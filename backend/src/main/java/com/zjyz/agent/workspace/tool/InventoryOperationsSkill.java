package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.pojo.param.req.QueryInventoryAnomalyParam;
import com.zjyz.pojo.param.ret.InventoryAnomalyListRet;
import com.zjyz.pojo.param.ret.InventoryAnomalyRet;
import com.zjyz.pojo.param.ret.InventoryDashboardRet;
import com.zjyz.service.InventoryService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * D1 库存运营汇总（inventory.operations_summary）：复用库存工作台的确定性口径，
 * 输出异常材料全集计数与明细（负库存/在租为负/租入未退为负/长期无流水）。
 * 企业全量口径（IGNORES_SELECTION）；数量为各材料自身计数单位，不做跨单位合计。
 */
@Component
public class InventoryOperationsSkill {
    public static final String TOOL_CODE = "inventory.operations_summary";
    private static final List<String> ANOMALY_CODES = Arrays.asList(
            "NEGATIVE_INVENTORY", "NEGATIVE_RENTED", "NEGATIVE_LEASED", "LONG_TIME_NO_FLOW");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    public InventoryOperationsSkill(InventoryService inventoryService, ObjectMapper objectMapper) {
        this.inventoryService = inventoryService;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson, String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null) {
            throw new MyBizException("工作空间上下文缺失", "AGT400");
        }
        Query query = parseQuery(argumentsJson, originalMessage);
        InventoryDashboardRet dashboard = inventoryService.queryInventoryDashboard(null, null);
        InventoryDashboardRet.AnomalySummary summary = dashboard == null || dashboard.getAnomalySummary() == null
                ? new InventoryDashboardRet.AnomalySummary() : dashboard.getAnomalySummary();

        QueryInventoryAnomalyParam anomalyParam = new QueryInventoryAnomalyParam();
        anomalyParam.setPageNum(1);
        anomalyParam.setPageSize(query.limit);
        anomalyParam.setAnomalyCode(query.anomalyCode);
        anomalyParam.setSeverity(query.severity);
        InventoryAnomalyListRet anomalies = inventoryService.queryInventoryAnomalies(anomalyParam);
        long totalCount = anomalies == null || anomalies.getTotalNum() == null ? 0L : anomalies.getTotalNum();
        List<InventoryAnomalyRet> displayed = anomalies == null || anomalies.getList() == null
                ? Collections.emptyList() : anomalies.getList();

        Map<String, Object> summaryCard = new LinkedHashMap<>();
        summaryCard.put("totalAnomalyMaterials", safeInt(summary.getTotalAnomalyMaterials()));
        summaryCard.put("negativeInventoryCount", safeInt(summary.getNegativeInventoryCount()));
        summaryCard.put("negativeRentedCount", safeInt(summary.getNegativeRentedCount()));
        summaryCard.put("negativeLeasedCount", safeInt(summary.getNegativeLeasedCount()));
        summaryCard.put("stagnantCount", safeInt(summary.getStagnantCount()));

        List<Map<String, Object>> items = new ArrayList<>();
        for (InventoryAnomalyRet anomaly : displayed) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialId", anomaly.getMaterialId());
            item.put("materialName", anomaly.getMaterialName());
            item.put("materialSpecification", anomaly.getMaterialSpecification());
            item.put("anomalyCode", anomaly.getAnomalyCode());
            item.put("anomalyName", anomaly.getAnomalyName());
            item.put("severity", anomaly.getSeverity());
            item.put("currentValue", anomaly.getCurrentValue());
            item.put("suggestion", anomaly.getSuggestion());
            items.add(item);
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "inventory-operations-summary");
        card.put("schemaVersion", "1.0");
        card.put("metricVersion", "inventory-operations-v1");
        card.put("asOfDate", LocalDate.now().toString());
        card.put("anomalySummary", summaryCard);
        card.put("anomalyCodeFilter", query.anomalyCode);
        card.put("severityFilter", query.severity);
        card.put("totalCount", totalCount);
        card.put("displayedCount", items.size());
        card.put("truncated", totalCount > items.size());
        card.put("limit", query.limit);
        card.put("items", items);
        card.put("scopeNote", "企业全量库存口径，忽略项目多选；currentValue 为该材料自身计数单位的数量，不做跨单位合计。"
                + "长期无流水阈值为工作台固定 30 天口径。");

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + LocalDate.now());
        evidence.setSkills(Collections.singletonList("InventoryOperationsSkill"));
        evidence.setApiList(Arrays.asList("internal:InventoryService.queryInventoryDashboard",
                "internal:InventoryService.queryInventoryAnomalies"));
        evidence.setRecordCount((int) Math.min(totalCount, Integer.MAX_VALUE));
        Map<String,Object> criteria = new LinkedHashMap<>();
        criteria.put("asOfDate", LocalDate.now().toString());
        if (query.anomalyCode != null) criteria.put("anomalyCode", query.anomalyCode);
        if (query.severity != null) criteria.put("severity", query.severity);
        criteria.put("limit", query.limit);
        evidence.setCriteria(criteria);
        evidence.setAvailability("AVAILABLE");
        evidence.setCompleteness(totalCount > items.size() ? "PARTIAL" : "COMPLETE");


        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("inventory_operations_summary");
        execution.setConfidence(0.92d);
        execution.setAnswer(buildAnswer(summary, query, totalCount, displayed));
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(evidence);
        return execution;
    }

    private String buildAnswer(InventoryDashboardRet.AnomalySummary summary,
                               Query query,
                               long totalCount,
                               List<InventoryAnomalyRet> displayed) {
        StringBuilder answer = new StringBuilder("企业全量库存口径：当前异常材料共 ")
                .append(safeInt(summary.getTotalAnomalyMaterials())).append(" 种（库存为负 ")
                .append(safeInt(summary.getNegativeInventoryCount())).append("、在租为负 ")
                .append(safeInt(summary.getNegativeRentedCount())).append("、租入未退为负 ")
                .append(safeInt(summary.getNegativeLeasedCount())).append("、长期无流水 ")
                .append(safeInt(summary.getStagnantCount())).append("）。");
        String filterLabel = StringUtils.hasText(query.anomalyCode)
                ? "筛选 " + query.anomalyCode : "全部异常";
        if (totalCount == 0) {
            answer.append(filterLabel).append("下没有匹配记录。");
            return answer.toString();
        }
        answer.append(filterLabel).append("匹配 ").append(totalCount).append(" 项");
        if (totalCount > displayed.size()) {
            answer.append("，结果卡展示前 ").append(displayed.size()).append(" 项");
        }
        answer.append("，材料明细见结果卡。不同材料的数量单位未混合相加。");
        return answer.toString();
    }

    private Query parseQuery(String argumentsJson, String originalMessage) {
        Query query = new Query();
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                String anomalyCode = trimToNull(root.path("anomalyCode").asText(null));
                if (anomalyCode != null) {
                    String normalized = anomalyCode.toUpperCase(Locale.ROOT);
                    if (!ANOMALY_CODES.contains(normalized)) {
                        throw new MyBizException("不支持的库存异常编码：" + anomalyCode, "AGT400");
                    }
                    query.anomalyCode = normalized;
                }
                String severity = trimToNull(root.path("severity").asText(null));
                if (severity != null) {
                    String normalized = severity.toUpperCase(Locale.ROOT);
                    if (!"HIGH".equals(normalized) && !"LOW".equals(normalized)) {
                        throw new MyBizException("severity 仅支持 HIGH 或 LOW", "AGT400");
                    }
                    query.severity = normalized;
                }
                int limit = root.path("limit").asInt(DEFAULT_LIMIT);
                query.limit = Math.max(1, Math.min(limit, MAX_LIMIT));
            } catch (MyBizException e) {
                throw e;
            } catch (Exception e) {
                throw new MyBizException("库存运营查询参数格式不正确", "AGT400");
            }
        }
        String source = safe(originalMessage);
        if (query.anomalyCode == null) {
            if (source.contains("负库存") || source.contains("库存为负")) {
                query.anomalyCode = "NEGATIVE_INVENTORY";
            } else if (source.contains("无流水") || source.contains("沉淀") || source.contains("停滞")) {
                query.anomalyCode = "LONG_TIME_NO_FLOW";
            }
        }
        return query;
    }

    private int safeInt(Integer value) { return value == null ? 0 : value; }
    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String safe(String value) { return value == null ? "" : value; }

    private static class Query {
        private String anomalyCode;
        private String severity;
        private int limit = DEFAULT_LIMIT;
    }
}
