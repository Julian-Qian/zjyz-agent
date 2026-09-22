package com.zjyz.agent.workspace.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Query;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Result;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Summary;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ReceivableCollectionSkill {
    public static final String TOOL_CODE = "finance.receivable_collection_list";
    public static final String ARTIFACT_TYPE = "RECEIVABLE_COLLECTION";
    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})年");
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern WAN_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*万");
    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)\\s*天");

    private final ReceivableCollectionService service;
    private final ObjectMapper objectMapper;

    public ReceivableCollectionSkill(ReceivableCollectionService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson, String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null || !"ALL".equalsIgnoreCase(workspace.getSelectionMode())) {
            throw new MyBizException("应收催缴清单只能在选择全部项目时执行", "AGT403");
        }
        if (!Boolean.TRUE.equals(workspace.getFinanceEnabled())) {
            throw new MyBizException("企业应收催缴能力尚未开通", "AGT403");
        }
        Query query = parseQuery(argumentsJson, originalMessage);
        Result result = service.generate(workspace.getCid(), query);
        Summary summary = result.getSummary();

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "receivable-collection-list");
        card.put("schemaVersion", "1.0");
        card.put("asOfDate", result.getQuery().getAsOfDate());
        card.put("year", result.getQuery().getYear());
        card.put("scope", result.getQuery().getScope());
        card.put("summary", summary);
        card.put("items", result.getItems().stream().limit(20).collect(Collectors.toList()));
        card.put("displayedCount", Math.min(result.getItems().size(), 20));
        card.put("totalArtifactCount", result.getItems().size());
        card.put("warnings", result.getWarnings());
        card.put("exportAvailable", true);

        String answer = buildAnswer(summary, result);
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + result.getQuery().getAsOfDate());
        evidence.setSkills(Collections.singletonList("ReceivableCollectionListSkill"));
        evidence.setApiList(Arrays.asList(
                "internal:settlement_receivable_period",
                "internal:customer_payment_allocation",
                "internal:customer_payment",
                "internal:project"));
        evidence.setRecordCount(summary.getReturnedProjectCount());

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("finance_receivable_collection");
        execution.setConfidence(1.0d);
        execution.setAnswer(answer);
        execution.setCards(Collections.singletonList(card));
        execution.setWarnings(result.getWarnings());
        execution.setEvidence(evidence);
        execution.setArtifactType(ARTIFACT_TYPE);
        execution.setArtifactTitle(result.getQuery().getAsOfDate() + " 应收催缴清单");
        execution.setArtifactMimeType("application/json");
        try {
            execution.setArtifactContentJson(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            throw new MyBizException("催缴清单产物生成失败", "FIN500");
        }
        return execution;
    }

    private Query parseQuery(String argumentsJson, String message) {
        Query query = new Query();
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                if (root.hasNonNull("asOfDate")) query.setAsOfDate(root.get("asOfDate").asText());
                if (root.hasNonNull("year")) query.setYear(root.get("year").asInt());
                if (root.hasNonNull("scope")) query.setScope(root.get("scope").asText());
                if (root.hasNonNull("minOutstandingAmount")) query.setMinOutstandingAmount(root.get("minOutstandingAmount").decimalValue());
                if (root.hasNonNull("minOverdueDays")) query.setMinOverdueDays(root.get("minOverdueDays").asInt());
                if (root.hasNonNull("projectKeyword")) query.setProjectKeyword(root.get("projectKeyword").asText());
                if (root.hasNonNull("customerKeyword")) query.setCustomerKeyword(root.get("customerKeyword").asText());
                if (root.hasNonNull("managerName")) query.setManagerName(root.get("managerName").asText());
                if (root.hasNonNull("sortBy")) query.setSortBy(root.get("sortBy").asText());
                if (root.hasNonNull("limit")) query.setLimit(root.get("limit").asInt());
                if (root.hasNonNull("includeClosedLate")) query.setIncludeClosedLate(root.get("includeClosedLate").asBoolean());
                if (root.has("priorityLevels") && root.get("priorityLevels").isArray()) {
                    List<String> levels = new ArrayList<>();
                    root.get("priorityLevels").forEach(node -> levels.add(node.asText()));
                    query.setPriorityLevels(levels);
                }
            } catch (Exception e) {
                throw new MyBizException("应收催缴查询参数格式不正确", "FIN400");
            }
        }
        enrichFromMessage(query, message);
        return query;
    }

    private void enrichFromMessage(Query query, String message) {
        LocalDate today = LocalDate.now();
        if (!StringUtils.hasText(query.getAsOfDate())) {
            Matcher dateMatcher = DATE_PATTERN.matcher(safe(message));
            query.setAsOfDate(dateMatcher.find() ? dateMatcher.group(1) : today.toString());
        }
        if (query.getYear() == null) {
            Matcher yearMatcher = YEAR_PATTERN.matcher(safe(message));
            query.setYear(yearMatcher.find() ? Integer.parseInt(yearMatcher.group(1)) : today.getYear());
        }
        if (!StringUtils.hasText(query.getScope())) {
            if (containsAny(message, "只看往年", "历史欠款", "历史遗留", "以前年度")) query.setScope("HISTORICAL_CARRYOVER");
            else if (containsAny(message, "只看本年", "只看今年", "本年度到期")) query.setScope("DUE_IN_YEAR");
            else query.setScope("ALL_OVERDUE");
        }
        if (query.getMinOutstandingAmount() == null) {
            Matcher amountMatcher = WAN_PATTERN.matcher(safe(message));
            if (amountMatcher.find()) query.setMinOutstandingAmount(new BigDecimal(amountMatcher.group(1)).multiply(new BigDecimal("10000")));
        }
        if (query.getMinOverdueDays() == null) {
            Matcher daysMatcher = DAYS_PATTERN.matcher(safe(message));
            if (daysMatcher.find()) query.setMinOverdueDays(Integer.parseInt(daysMatcher.group(1)));
        }
    }

    private String buildAnswer(Summary summary, Result result) {
        if (summary.getReturnedProjectCount() == 0) {
            return "截至 " + result.getQuery().getAsOfDate() + "，按当前筛选口径没有查到仍未清的逾期应收项目。";
        }
        return "截至 " + result.getQuery().getAsOfDate() + "，共识别 " + summary.getProjectCount()
                + " 个逾期未清项目，清单金额合计 " + formatMoney(summary.getTotalOutstanding())
                + " 元；其中本年到期 " + formatMoney(summary.getCurrentYearOutstanding())
                + " 元、历史结转 " + formatMoney(summary.getCarryoverOutstanding())
                + " 元，P1 级 " + summary.getP1Count() + " 个。对话中展示前20项，完整明细可从产物下载 Excel。";
    }

    private String formatMoney(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private boolean containsAny(String text, String... values) {
        String source = safe(text);
        for (String value : values) if (source.contains(value)) return true;
        return false;
    }

    private String safe(String value) { return value == null ? "" : value; }
}
