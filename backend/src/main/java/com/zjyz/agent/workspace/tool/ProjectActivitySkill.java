package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.ReconciliationDocumentMapper;
import com.zjyz.dao.RentDocumentMapper;
import com.zjyz.dao.RentInDocumentMapper;
import com.zjyz.dao.RentInReturnDocumentMapper;
import com.zjyz.dao.ReturnDocumentMapper;
import com.zjyz.dao.SettlementDocumentMapper;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.ReconciliationDocumentEntity;
import com.zjyz.pojo.entity.RentDocumentEntity;
import com.zjyz.pojo.entity.RentInDocumentEntity;
import com.zjyz.pojo.entity.RentInReturnDocumentEntity;
import com.zjyz.pojo.entity.ReturnDocumentEntity;
import com.zjyz.pojo.entity.SettlementDocumentEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ProjectActivitySkill {
    public static final String TOOL_CODE = "project.activity";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d{1,4})\\s*天");
    private static final Set<String> ALL_TYPES = new LinkedHashSet<>(Arrays.asList(
            "RENT_OUT", "RETURN", "RENT_IN", "RENT_IN_RETURN", "MATERIAL_RECONCILIATION", "FINANCIAL_RECONCILIATION"));

    private final ProjectMapper projectMapper;
    private final RentDocumentMapper rentDocumentMapper;
    private final ReturnDocumentMapper returnDocumentMapper;
    private final RentInDocumentMapper rentInDocumentMapper;
    private final RentInReturnDocumentMapper rentInReturnDocumentMapper;
    private final ReconciliationDocumentMapper reconciliationDocumentMapper;
    private final SettlementDocumentMapper settlementDocumentMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.runtime.timezone:${AGENT_TIMEZONE:Asia/Shanghai}}")
    private String runtimeTimezone;

    public ProjectActivitySkill(ProjectMapper projectMapper,
                                RentDocumentMapper rentDocumentMapper,
                                ReturnDocumentMapper returnDocumentMapper,
                                RentInDocumentMapper rentInDocumentMapper,
                                RentInReturnDocumentMapper rentInReturnDocumentMapper,
                                ReconciliationDocumentMapper reconciliationDocumentMapper,
                                SettlementDocumentMapper settlementDocumentMapper,
                                ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.rentDocumentMapper = rentDocumentMapper;
        this.returnDocumentMapper = returnDocumentMapper;
        this.rentInDocumentMapper = rentInDocumentMapper;
        this.rentInReturnDocumentMapper = rentInReturnDocumentMapper;
        this.reconciliationDocumentMapper = reconciliationDocumentMapper;
        this.settlementDocumentMapper = settlementDocumentMapper;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson,
                                       String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        Query query = parseQuery(argumentsJson, originalMessage);
        List<ProjectEntity> projects = queryProjects(workspace, query);
        List<String> projectIds = projects.stream().map(ProjectEntity::getProjectId).collect(Collectors.toList());
        Map<String, ActivitySummary> summaries = new LinkedHashMap<>();
        projects.forEach(project -> summaries.put(project.getProjectId(), new ActivitySummary()));
        loadActivities(workspace.getCid(), projectIds, query.activityTypes, query.asOfDate, summaries);

        List<Map<String, Object>> allItems = new ArrayList<>();
        for (ProjectEntity project : projects) {
            ActivitySummary summary = summaries.get(project.getProjectId());
            LocalDate baseline = summary.lastActivityDate != null
                    ? summary.lastActivityDate : parseDate(project.getCreateDate());
            long inactiveDays = baseline == null ? -1L : Math.max(0L, ChronoUnit.DAYS.between(baseline, query.asOfDate));
            boolean inactive = inactiveDays < 0 || inactiveDays >= query.minInactiveDays;
            if (query.onlyInactive && !inactive) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("projectId", project.getProjectId());
            item.put("projectName", project.getProjectName());
            item.put("projectBusinessType", project.getProjectBusinessType());
            item.put("projectStatusFlag", project.getProjectStatusFlag());
            item.put("managerName", project.getManagerName());
            item.put("customerName", firstText(project.getTenantUnit(), project.getPartnerName()));
            item.put("lastActivityDate", summary.lastActivityDate == null ? null : summary.lastActivityDate.toString());
            item.put("lastActivityType", summary.lastActivityType);
            item.put("inactiveDays", inactiveDays < 0 ? null : inactiveDays);
            item.put("activityCounts", summary.counts);
            item.put("evidenceStatus", summary.lastActivityDate == null ? "NO_ACTIVITY_RECORD" : "CONFIRMED");
            allItems.add(item);
        }
        allItems.sort(Comparator
                .comparingLong((Map<String, Object> item) -> item.get("inactiveDays") == null
                        ? Long.MAX_VALUE : ((Number) item.get("inactiveDays")).longValue()).reversed()
                .thenComparing(item -> safe((String) item.get("projectName"))));
        List<Map<String, Object>> displayed = allItems.stream().limit(query.limit).collect(Collectors.toList());

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "project-activity-list");
        card.put("schemaVersion", "1.0");
        card.put("asOfDate", query.asOfDate.toString());
        card.put("minInactiveDays", query.minInactiveDays);
        card.put("activityTypes", query.activityTypes);
        card.put("total", allItems.size());
        card.put("displayedCount", displayed.size());
        card.put("items", displayed);
        card.put("scopeNote", "无活动天数以所选单据类型中最近业务日期为准；完全没有单据时以项目录入日期作为观察起点。");

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + query.asOfDate + ", minInactiveDays=" + query.minInactiveDays);
        evidence.setSkills(Collections.singletonList("ProjectActivitySkill"));
        evidence.setApiList(Arrays.asList("internal:rent_document", "internal:return_document", "internal:rent_in_document",
                "internal:rent_in_return_document", "internal:reconciliation_document", "internal:settlement_document"));
        evidence.setRecordCount(allItems.size());

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("project_activity");
        execution.setConfidence(0.95d);
        execution.setAnswer(buildAnswer(query, allItems));
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(evidence);
        return execution;
    }

    private List<ProjectEntity> queryProjects(AgentRuntimeRecords.Workspace workspace, Query query) {
        if (workspace == null) throw new MyBizException("工作空间上下文缺失", "AGT400");
        QueryWrapper<ProjectEntity> wrapper = new QueryWrapper<ProjectEntity>().eq("cid", workspace.getCid());
        if ("EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())) {
            if (workspace.getProjectIds() == null || workspace.getProjectIds().isEmpty()) throw new MyBizException("项目范围为空", "AGT400");
            wrapper.in("project_id", workspace.getProjectIds());
        }
        if (StringUtils.hasText(query.projectStatusFlag)) wrapper.eq("project_status_flag", query.projectStatusFlag);
        if (StringUtils.hasText(query.keyword)) {
            wrapper.and(value -> value.like("project_name", query.keyword)
                    .or().like("manager_name", query.keyword)
                    .or().like("partner_name", query.keyword)
                    .or().like("tenant_unit", query.keyword));
        }
        return projectMapper.selectList(wrapper);
    }

    private void loadActivities(String cid,
                                List<String> projectIds,
                                Set<String> types,
                                LocalDate asOfDate,
                                Map<String, ActivitySummary> summaries) {
        if (projectIds.isEmpty()) return;
        if (types.contains("RENT_OUT")) {
            for (RentDocumentEntity value : rentDocumentMapper.selectList(scope(RentDocumentEntity.class, cid, projectIds))) {
                record(summaries, value.getProjectId(), "RENT_OUT", firstDate(value.getRentDate(), value.getCreateDate()), asOfDate);
            }
        }
        if (types.contains("RETURN")) {
            for (ReturnDocumentEntity value : returnDocumentMapper.selectList(scope(ReturnDocumentEntity.class, cid, projectIds))) {
                record(summaries, value.getProjectId(), "RETURN", firstDate(value.getReturnDate(), value.getCreateDate()), asOfDate);
            }
        }
        if (types.contains("RENT_IN")) {
            for (RentInDocumentEntity value : rentInDocumentMapper.selectList(scope(RentInDocumentEntity.class, cid, projectIds))) {
                record(summaries, value.getProjectId(), "RENT_IN", firstDate(value.getRentInDate(), value.getCreateDate()), asOfDate);
            }
        }
        if (types.contains("RENT_IN_RETURN")) {
            for (RentInReturnDocumentEntity value : rentInReturnDocumentMapper.selectList(scope(RentInReturnDocumentEntity.class, cid, projectIds))) {
                record(summaries, value.getProjectId(), "RENT_IN_RETURN", firstDate(value.getRentInReturnDate(), value.getCreateDate()), asOfDate);
            }
        }
        if (types.contains("MATERIAL_RECONCILIATION")) {
            for (ReconciliationDocumentEntity value : reconciliationDocumentMapper.selectList(scope(ReconciliationDocumentEntity.class, cid, projectIds))) {
                record(summaries, value.getProjectId(), "MATERIAL_RECONCILIATION", firstDate(value.getReconciliationDate(), value.getCreateDate()), asOfDate);
            }
        }
        if (types.contains("FINANCIAL_RECONCILIATION")) {
            for (SettlementDocumentEntity value : settlementDocumentMapper.selectList(scope(SettlementDocumentEntity.class, cid, projectIds))) {
                if (StringUtils.hasText(value.getType()) && !"3".equals(value.getType())) continue;
                record(summaries, value.getProjectId(), "FINANCIAL_RECONCILIATION", firstDate(value.getEndDate(), value.getCreateDate()), asOfDate);
            }
        }
    }

    private <T> QueryWrapper<T> scope(Class<T> ignored, String cid, List<String> projectIds) {
        return new QueryWrapper<T>().eq("cid", cid).in("project_id", projectIds);
    }

    private void record(Map<String, ActivitySummary> summaries,
                        String projectId,
                        String type,
                        LocalDate date,
                        LocalDate asOfDate) {
        ActivitySummary summary = summaries.get(projectId);
        if (summary == null || date == null || date.isAfter(asOfDate)) return;
        summary.counts.put(type, summary.counts.getOrDefault(type, 0) + 1);
        if (summary.lastActivityDate == null || date.isAfter(summary.lastActivityDate)) {
            summary.lastActivityDate = date;
            summary.lastActivityType = type;
        }
    }

    private Query parseQuery(String argumentsJson, String message) {
        Query query = new Query();
        query.asOfDate = LocalDate.now(resolveZone());
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                query.asOfDate = parseRequiredDate(root.path("asOfDate").asText(null), query.asOfDate);
                query.minInactiveDays = Math.max(0, root.path("minInactiveDays").asInt(0));
                query.projectStatusFlag = normalizeProjectStatus(root.path("projectStatus").asText(null));
                query.onlyInactive = !root.has("onlyInactive") || root.path("onlyInactive").asBoolean(true);
                query.keyword = trimToNull(root.path("keyword").asText(null));
                query.limit = clamp(root.path("limit").asInt(DEFAULT_LIMIT));
                if (root.path("activityTypes").isArray()) {
                    root.path("activityTypes").forEach(value -> query.activityTypes.add(normalizeActivityType(value.asText())));
                }
            } catch (MyBizException e) {
                throw e;
            } catch (Exception e) {
                throw new MyBizException("项目活动查询参数格式不正确", "AGT400");
            }
        }
        if (query.activityTypes.isEmpty()) query.activityTypes.addAll(ALL_TYPES);
        if (query.minInactiveDays == 0) {
            Matcher matcher = DAYS_PATTERN.matcher(safe(message));
            if (matcher.find()) query.minInactiveDays = Integer.parseInt(matcher.group(1));
        }
        if (!StringUtils.hasText(query.projectStatusFlag)) {
            if (containsAny(message, "进行中", "未完成")) query.projectStatusFlag = "0";
            else if (containsAny(message, "已完成", "已结束")) query.projectStatusFlag = "1";
        }
        if (query.activityTypes.equals(ALL_TYPES)) {
            if (containsAny(message, "租出或归还", "租出/归还", "租出和归还")) {
                query.activityTypes.clear();
                query.activityTypes.add("RENT_OUT");
                query.activityTypes.add("RETURN");
            }
        }
        return query;
    }

    private String buildAnswer(Query query, List<Map<String, Object>> items) {
        if (items.isEmpty()) return "截至 " + query.asOfDate + "，当前范围没有达到连续 " + query.minInactiveDays + " 天无所选业务活动的项目。";
        String preview = items.stream().limit(8)
                .map(item -> item.get("projectName") + "（最近活动："
                        + (item.get("lastActivityDate") == null ? "无记录" : item.get("lastActivityDate"))
                        + "，无活动" + (item.get("inactiveDays") == null ? "天数未知" : item.get("inactiveDays") + "天") + "）")
                .collect(Collectors.joining("、"));
        return "截至 " + query.asOfDate + "，当前范围共有 " + items.size() + " 个项目达到连续 "
                + query.minInactiveDays + " 天无所选业务活动。前8项：" + preview + "。";
    }

    private LocalDate firstDate(String... values) { for (String value : values) { LocalDate parsed = parseDate(value); if (parsed != null) return parsed; } return null; }
    private LocalDate parseDate(String value) { try { return StringUtils.hasText(value) && value.length() >= 10 ? LocalDate.parse(value.substring(0, 10)) : null; } catch (Exception e) { return null; } }
    private LocalDate parseRequiredDate(String value, LocalDate fallback) { if (!StringUtils.hasText(value)) return fallback; LocalDate parsed = parseDate(value); if (parsed == null) throw new MyBizException("日期格式应为yyyy-MM-dd", "AGT400"); return parsed; }
    private String normalizeActivityType(String value) { String normalized = safe(value).toUpperCase(Locale.ROOT); if (!ALL_TYPES.contains(normalized)) throw new MyBizException("业务活动类型不正确", "AGT400"); return normalized; }
    private String normalizeProjectStatus(String value) { if (!StringUtils.hasText(value) || "ALL".equalsIgnoreCase(value)) return null; if ("ONGOING".equalsIgnoreCase(value) || "0".equals(value)) return "0"; if ("COMPLETED".equalsIgnoreCase(value) || "1".equals(value)) return "1"; throw new MyBizException("项目状态筛选值不正确", "AGT400"); }
    private boolean containsAny(String text, String... keywords) { for (String keyword : keywords) if (safe(text).contains(keyword)) return true; return false; }
    private String firstText(String... values) { for (String value : values) if (StringUtils.hasText(value)) return value; return null; }
    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private int clamp(int value) { return Math.max(1, Math.min(value, MAX_LIMIT)); }
    private String safe(String value) { return value == null ? "" : value; }
    private ZoneId resolveZone() { try { return ZoneId.of(runtimeTimezone); } catch (Exception e) { return ZoneId.of("Asia/Shanghai"); } }

    private static class Query {
        private LocalDate asOfDate;
        private int minInactiveDays;
        private String projectStatusFlag;
        private boolean onlyInactive = true;
        private String keyword;
        private int limit = DEFAULT_LIMIT;
        private final Set<String> activityTypes = new LinkedHashSet<>();
    }

    private static class ActivitySummary {
        private LocalDate lastActivityDate;
        private String lastActivityType;
        private final Map<String, Integer> counts = new LinkedHashMap<>();
    }
}
