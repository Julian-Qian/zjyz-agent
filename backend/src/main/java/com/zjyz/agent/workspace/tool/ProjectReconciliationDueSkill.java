package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.ContractMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.SettlementDocumentMapper;
import com.zjyz.pojo.entity.ContractEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.SettlementDocumentEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ProjectReconciliationDueSkill {
    public static final String TOOL_CODE = "project.reconciliation_due";
    private static final String SETTLEMENT_TYPE_FINANCE = "3";
    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("(20\\d{2})[-年](\\d{1,2})月?");
    private static final Pattern MONTH_PATTERN = Pattern.compile("(?<!\\d)(1[0-2]|0?[1-9])月");
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2}-\\d{1,2}-\\d{1,2})");
    private static final Pattern DAYS_PATTERN = Pattern.compile("(?:超过|至少|连续)?\\s*(\\d{1,4})\\s*天(?:未对账|没有对账)?");
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final ProjectMapper projectMapper;
    private final ContractMapper contractMapper;
    private final SettlementDocumentMapper settlementDocumentMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.runtime.timezone:${AGENT_TIMEZONE:Asia/Shanghai}}")
    private String runtimeTimezone;

    public ProjectReconciliationDueSkill(ProjectMapper projectMapper,
                                         ContractMapper contractMapper,
                                         SettlementDocumentMapper settlementDocumentMapper,
                                         ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.contractMapper = contractMapper;
        this.settlementDocumentMapper = settlementDocumentMapper;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson, String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null) {
            throw new MyBizException("工作空间上下文缺失", "AGT400");
        }
        Query query = parseQuery(argumentsJson, originalMessage);
        List<ProjectEntity> projects = queryProjects(workspace, query.keyword);
        List<DueItem> dueItems = new ArrayList<>();
        for (ProjectEntity project : projects) {
            if ("rent_in".equalsIgnoreCase(project.getProjectBusinessType())) {
                continue;
            }
            DueItem item = buildDueItem(project, query.dueEnd(), query.minUnreconciledDays);
            if (item != null) {
                dueItems.add(item);
            }
        }
        dueItems.sort(Comparator
                .comparing((DueItem item) -> item.lastSettledEnd == null ? LocalDate.MIN : item.lastSettledEnd)
                .thenComparing(item -> safe(item.projectName)));

        List<DueItem> displayed = dueItems.stream().limit(query.limit).collect(Collectors.toList());
        List<Map<String, Object>> cardItems = displayed.stream().map(this::cardItem).collect(Collectors.toList());

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "reconciliation-due-list");
        card.put("schemaVersion", "1.0");
        card.put("selectionMode", workspace.getSelectionMode());
        card.put("targetMonth", query.targetMonth == null ? null : query.targetMonth.toString());
        card.put("asOfDate", query.dueEnd().toString());
        card.put("minUnreconciledDays", query.minUnreconciledDays);
        card.put("total", dueItems.size());
        card.put("displayedCount", cardItems.size());
        card.put("items", cardItems);
        card.put("scopeNote", "按租出项目最近一张财务对账单截止日期判断；租入项目暂不纳入财务对账待办口径。");

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + query.dueEnd() + ", minUnreconciledDays=" + query.minUnreconciledDays);
        evidence.setSkills(Collections.singletonList("ProjectReconciliationDueSkill"));
        evidence.setApiList(java.util.Arrays.asList("internal:project", "internal:contract", "internal:settlement_document"));
        evidence.setRecordCount(dueItems.size());
        Map<String,Object> criteria = new LinkedHashMap<>();
        criteria.put("asOfDate", query.dueEnd().toString());
        if (query.targetMonth != null) criteria.put("targetMonth", query.targetMonth.toString());
        if (query.keyword != null) criteria.put("keyword", query.keyword);
        criteria.put("minUnreconciledDays", query.minUnreconciledDays);
        criteria.put("limit", query.limit);
        evidence.setCriteria(criteria);
        evidence.setAvailability("AVAILABLE");
        evidence.setCompleteness(dueItems.size() > displayed.size() ? "PARTIAL" : "COMPLETE");


        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("project_reconciliation_due");
        execution.setConfidence(0.92d);
        execution.setAnswer(buildAnswer(workspace, query, dueItems, displayed.size()));
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(evidence);
        return execution;
    }

    private List<ProjectEntity> queryProjects(AgentRuntimeRecords.Workspace workspace, String keyword) {
        QueryWrapper<ProjectEntity> wrapper = new QueryWrapper<ProjectEntity>()
                .eq("cid", workspace.getCid());
        if ("EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())) {
            if (workspace.getProjectIds() == null || workspace.getProjectIds().isEmpty()) {
                throw new MyBizException("项目范围为空", "AGT400");
            }
            wrapper.in("project_id", workspace.getProjectIds());
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(item -> item.like("project_name", keyword)
                    .or().like("manager_name", keyword)
                    .or().like("partner_name", keyword)
                    .or().like("tenant_unit", keyword));
        }
        wrapper.orderByDesc("create_date", "project_id");
        return projectMapper.selectList(wrapper);
    }

    private DueItem buildDueItem(ProjectEntity project, LocalDate targetEnd, int minUnreconciledDays) {
        SettlementDocumentEntity latest = latestSettlement(project.getProjectId());
        LocalDate lastEnd = parseDate(latest == null ? null : latest.getEndDate());
        if (lastEnd != null && !lastEnd.isBefore(targetEnd)) {
            return null;
        }
        ContractEntity contract = latestContract(project.getProjectId());
        LocalDate dueStart = lastEnd == null ? firstDate(contract == null ? null : contract.getStartDate(), project.getCreateDate())
                : lastEnd.plusDays(1);
        if (dueStart != null && dueStart.isAfter(targetEnd)) {
            return null;
        }
        long unreconciledDays = dueStart == null ? 0L : ChronoUnit.DAYS.between(dueStart, targetEnd) + 1L;
        if (unreconciledDays < Math.max(minUnreconciledDays, 0)) {
            return null;
        }
        DueItem item = new DueItem();
        item.projectId = project.getProjectId();
        item.projectName = project.getProjectName();
        item.managerName = project.getManagerName();
        item.customerName = firstText(project.getTenantUnit(), project.getPartnerName(), contract == null ? null : contract.getCustomerName());
        item.contractName = contract == null ? null : contract.getContractName();
        item.reconciliationPeriod = contract == null ? null : contract.getReconciliationPeriod();
        item.lastSettlementDocumentId = latest == null ? null : latest.getSettlementDocumentId();
        item.lastSettlementDocumentName = latest == null ? null : latest.getSettlementDocumentName();
        item.lastSettledEnd = lastEnd;
        item.dueStart = dueStart;
        item.dueEnd = targetEnd;
        item.unreconciledDays = Math.max(unreconciledDays, 0L);
        item.reason = lastEnd == null ? "未找到财务对账单，建议从项目开始日期核对至目标月份末"
                : "最近财务对账截止到 " + lastEnd + "，早于目标月份末 " + targetEnd;
        return item;
    }

    private SettlementDocumentEntity latestSettlement(String projectId) {
        List<SettlementDocumentEntity> rows = settlementDocumentMapper.selectList(new QueryWrapper<SettlementDocumentEntity>()
                .eq("project_id", projectId)
                .eq("type", SETTLEMENT_TYPE_FINANCE)
                .and(w -> w.eq("status", "ACTIVE").or().isNull("status"))
                .orderByDesc("end_date", "create_date", "settlement_document_id"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ContractEntity latestContract(String projectId) {
        List<ContractEntity> rows = contractMapper.selectList(new QueryWrapper<ContractEntity>()
                .eq("project_id", projectId)
                .orderByDesc("create_date", "contract_id"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Query parseQuery(String argumentsJson, String message) {
        Query query = new Query();
        query.targetMonth = YearMonth.from(LocalDate.now(resolveZone()));
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                YearMonth parsed = parseYearMonth(root.path("targetMonth").asText(null));
                if (parsed != null) query.targetMonth = parsed;
                query.asOfDate = parseDate(root.path("asOfDate").asText(null));
                query.minUnreconciledDays = Math.max(0, root.path("minUnreconciledDays").asInt(0));
                query.keyword = trimToNull(root.path("keyword").asText(null));
                query.limit = clampLimit(root.path("limit").asInt(DEFAULT_LIMIT));
            } catch (Exception e) {
                throw new MyBizException("待对账查询参数格式不正确", "AGT400");
            }
        }
        if (query.limit <= 0) query.limit = DEFAULT_LIMIT;
        enrichFromMessage(query, message);
        return query;
    }

    private void enrichFromMessage(Query query, String message) {
        String source = safe(message);
        if (query.asOfDate == null) {
            Matcher date = DATE_PATTERN.matcher(source);
            if (date.find()) {
                String[] parts = date.group(1).split("-");
                query.asOfDate = LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            }
        }
        if (query.minUnreconciledDays <= 0 && source.contains("未对账")) {
            Matcher days = DAYS_PATTERN.matcher(source);
            if (days.find()) query.minUnreconciledDays = Integer.parseInt(days.group(1));
        }
        Matcher yearMonth = YEAR_MONTH_PATTERN.matcher(source);
        if (yearMonth.find()) {
            int year = Integer.parseInt(yearMonth.group(1));
            int month = Integer.parseInt(yearMonth.group(2));
            query.targetMonth = YearMonth.of(year, month);
            return;
        }
        Matcher month = MONTH_PATTERN.matcher(source);
        if (month.find()) {
            query.targetMonth = YearMonth.of(LocalDate.now(resolveZone()).getYear(), Integer.parseInt(month.group(1)));
        }
    }

    private String buildAnswer(AgentRuntimeRecords.Workspace workspace, Query query,
                               List<DueItem> dueItems, int displayedCount) {
        String scope = "ALL".equalsIgnoreCase(workspace.getSelectionMode())
                ? "本企业全部项目" : "当前选中的" + workspace.getProjectIds().size() + "个项目";
        if (dueItems.isEmpty()) {
            return scope + "截至 " + query.dueEnd() + " 暂未发现未对账达到 " + query.minUnreconciledDays + " 天的租出项目。"
                    + "判断口径：最近财务对账单截止日期已覆盖目标月份末，或项目尚未进入可对账区间。";
        }
        String preview = dueItems.stream().limit(10)
                .map(item -> safe(item.projectName) + "（上次对账至：" + (item.lastSettledEnd == null ? "未对账" : item.lastSettledEnd) + "）")
                .collect(Collectors.joining("、"));
        return scope + "截至 " + query.dueEnd() + " 未对账达到 " + query.minUnreconciledDays + " 天的租出项目共 " + dueItems.size()
                + " 个。" + (StringUtils.hasText(preview) ? " 前10项：" + preview + "。" : "")
                + (dueItems.size() > displayedCount ? " 当前结果卡展示前 " + displayedCount + " 项。" : "")
                + " 数据依据为项目、合同结算周期和最近财务对账单截止日期。";
    }

    private Map<String, Object> cardItem(DueItem item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("projectId", item.projectId);
        value.put("projectName", item.projectName);
        value.put("managerName", item.managerName);
        value.put("customerName", item.customerName);
        value.put("contractName", item.contractName);
        value.put("reconciliationPeriod", item.reconciliationPeriod);
        value.put("lastSettlementDocumentId", item.lastSettlementDocumentId);
        value.put("lastSettlementDocumentName", item.lastSettlementDocumentName);
        value.put("lastSettledEnd", item.lastSettledEnd == null ? null : item.lastSettledEnd.toString());
        value.put("dueStart", item.dueStart == null ? null : item.dueStart.toString());
        value.put("dueEnd", item.dueEnd == null ? null : item.dueEnd.toString());
        value.put("unreconciledDays", item.unreconciledDays);
        value.put("reason", item.reason);
        return value;
    }

    private YearMonth parseYearMonth(String value) {
        if (!StringUtils.hasText(value)) return null;
        return YearMonth.parse(value.trim());
    }

    private LocalDate firstDate(String... values) {
        for (String value : values) {
            LocalDate parsed = parseDate(value);
            if (parsed != null) return parsed;
        }
        return null;
    }

    private LocalDate parseDate(String value) {
        try {
            return StringUtils.hasText(value) && value.length() >= 10 ? LocalDate.parse(value.substring(0, 10)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }

    private int clampLimit(int value) { return Math.max(1, Math.min(value, MAX_LIMIT)); }
    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String safe(String value) { return value == null ? "" : value; }

    private ZoneId resolveZone() {
        try { return ZoneId.of(runtimeTimezone); }
        catch (Exception ignored) { return ZoneId.of("Asia/Shanghai"); }
    }

    private static class Query {
        private YearMonth targetMonth;
        private LocalDate asOfDate;
        private int minUnreconciledDays;
        private String keyword;
        private int limit = DEFAULT_LIMIT;

        private LocalDate dueEnd() {
            return asOfDate != null ? asOfDate : targetMonth.atEndOfMonth();
        }
    }

    private static class DueItem {
        private String projectId;
        private String projectName;
        private String managerName;
        private String customerName;
        private String contractName;
        private Integer reconciliationPeriod;
        private String lastSettlementDocumentId;
        private String lastSettlementDocumentName;
        private LocalDate lastSettledEnd;
        private LocalDate dueStart;
        private LocalDate dueEnd;
        private long unreconciledDays;
        private String reason;
    }
}
