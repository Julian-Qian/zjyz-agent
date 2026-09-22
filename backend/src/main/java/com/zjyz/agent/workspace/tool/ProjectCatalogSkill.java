package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.orch.AgentProjectOwnerQuery;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.pojo.entity.ProjectEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ProjectCatalogSkill {
    public static final String TOOL_CODE = "project.list";
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})年");
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final ProjectMapper projectMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.runtime.timezone:${AGENT_TIMEZONE:Asia/Shanghai}}")
    private String runtimeTimezone;

    public ProjectCatalogSkill(ProjectMapper projectMapper, ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson, String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null) {
            throw new MyBizException("工作空间上下文缺失", "AGT400");
        }
        Query query = parseQuery(argumentsJson, originalMessage);
        QueryWrapper<ProjectEntity> wrapper = new QueryWrapper<ProjectEntity>()
                .eq("cid", workspace.getCid());
        if ("EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())) {
            if (workspace.getProjectIds() == null || workspace.getProjectIds().isEmpty()) {
                throw new MyBizException("项目范围为空", "AGT400");
            }
            wrapper.in("project_id", workspace.getProjectIds());
        }
        if (StringUtils.hasText(query.businessType)) {
            wrapper.eq("project_business_type", query.businessType);
        }
        if (StringUtils.hasText(query.statusFlag)) {
            wrapper.eq("project_status_flag", query.statusFlag);
        }
        if (StringUtils.hasText(query.managerName)) wrapper.like("manager_name", query.managerName);
        if (StringUtils.hasText(query.partnerName)) wrapper.like("partner_name", query.partnerName);
        if (StringUtils.hasText(query.keyword)) {
            wrapper.and(item -> item.like("project_name", query.keyword)
                    .or().like("manager_name", query.keyword)
                    .or().like("partner_name", query.keyword)
                    .or().like("tenant_unit", query.keyword));
        }
        if (query.startDate != null) {
            wrapper.ge("create_date", query.startDate.toString());
        }
        if (query.endDate != null) {
            wrapper.lt("create_date", query.endDate.plusDays(1).toString());
        }
        wrapper.orderByDesc("create_date", "project_id");

        List<ProjectEntity> all = projectMapper.selectList(wrapper);
        int total = all.size();
        List<ProjectEntity> displayed = all.stream().limit(query.limit).collect(Collectors.toList());
        List<Map<String, Object>> items = displayed.stream().map(this::projectItem).collect(Collectors.toList());

        long rentOutCount = all.stream().filter(item -> !"rent_in".equalsIgnoreCase(item.getProjectBusinessType())).count();
        long rentInCount = all.stream().filter(item -> "rent_in".equalsIgnoreCase(item.getProjectBusinessType())).count();
        long ongoingCount = all.stream().filter(item -> "0".equals(item.getProjectStatusFlag())).count();
        long completedCount = all.stream().filter(item -> "1".equals(item.getProjectStatusFlag())).count();

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "project-list");
        card.put("schemaVersion", "1.0");
        card.put("selectionMode", workspace.getSelectionMode());
        card.put("managerName", query.managerName);
        card.put("partnerName", query.partnerName);
        card.put("startDate", query.startDate == null ? null : query.startDate.toString());
        card.put("endDate", query.endDate == null ? null : query.endDate.toString());
        card.put("total", total);
        card.put("displayedCount", items.size());
        card.put("rentOutCount", rentOutCount);
        card.put("rentInCount", rentInCount);
        card.put("ongoingCount", ongoingCount);
        card.put("completedCount", completedCount);
        card.put("items", items);
        card.put("scopeSummary", scopeSummary(workspace, query));

        String answer;
        if (total == 0) {
            answer = StringUtils.hasText(query.managerName)
                    ? "当前授权项目范围内，没有找到我方负责人姓名包含“" + query.managerName
                    + "”的项目。如果这是昵称，请补充系统登记的负责人姓名。"
                    : "按当前日期、项目范围和筛选条件，没有查询到录入项目。";
        } else {
            String preview = displayed.stream().limit(10)
                    .map(item -> safe(item.getProjectName()) + "（" + dateOnly(item.getCreateDate()) + "）")
                    .collect(Collectors.joining("、"));
            answer = scopeSummary(workspace, query) + "，共查询到 " + total + " 个项目：租出 " + rentOutCount
                    + " 个、租入 " + rentInCount + " 个；进行中 " + ongoingCount + " 个、已完成 " + completedCount
                    + " 个。" + (StringUtils.hasText(preview) ? (displayed.size() > 10 ? " 前10项：" : " 项目：") + preview + "。" : "")
                    + (total > displayed.size() ? " 当前结果卡展示前 " + displayed.size() + " 项。" : "");
        }

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange(timeRange(query));
        evidence.setSkills(Collections.singletonList("ProjectCatalogSkill"));
        evidence.setApiList(Collections.singletonList("internal:project"));
        evidence.setRecordCount(total);
        // Describe effective filters after normalization, never echo unexecuted model arguments.
        Map<String, Object> criteria = new LinkedHashMap<>();
        if (query.managerName != null) criteria.put("managerName", query.managerName);
        if (query.partnerName != null) criteria.put("partnerName", query.partnerName);
        if (query.keyword != null) criteria.put("keyword", query.keyword);
        if (query.businessType != null) criteria.put("projectBusinessType", query.businessType);
        if (query.statusFlag != null) criteria.put("status", "0".equals(query.statusFlag) ? "ONGOING" : "COMPLETED");
        if (query.startDate != null) criteria.put("startDate", query.startDate.toString());
        if (query.endDate != null) criteria.put("endDate", query.endDate.toString());
        criteria.put("asOfDate", LocalDate.now(resolveZone()).toString());
        criteria.put("limit", query.limit);
        evidence.setCriteria(criteria);
        evidence.setToolCode(TOOL_CODE);
        evidence.setAvailability("AVAILABLE");
        evidence.setCompleteness(total > displayed.size() ? "PARTIAL" : "COMPLETE");
        evidence.setQueriedAt(java.time.OffsetDateTime.now(resolveZone()).toString());


        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("project_list");
        execution.setConfidence(1.0d);
        execution.setAnswer(answer);
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(evidence);
        return execution;
    }

    private Query parseQuery(String argumentsJson, String message) {
        Query query = new Query();
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                query.startDate = parseDate(root.path("startDate").asText(null));
                query.endDate = parseDate(root.path("endDate").asText(null));
                query.businessType = normalizeBusinessType(root.path("projectBusinessType").asText(null));
                query.statusFlag = normalizeStatus(root.path("status").asText(null));
                query.keyword = trimToNull(root.path("keyword").asText(null));
                query.managerName = trimToNull(root.path("managerName").asText(null));
                query.partnerName = trimToNull(root.path("partnerName").asText(null));
                query.limit = clampLimit(root.path("limit").asInt(DEFAULT_LIMIT));
            } catch (Exception e) {
                throw new MyBizException("项目清单查询参数格式不正确", "AGT400");
            }
        }
        if (query.limit <= 0) query.limit = DEFAULT_LIMIT;
        enrichFromMessage(query, message);
        if (query.startDate != null && query.endDate != null && query.startDate.isAfter(query.endDate)) {
            throw new MyBizException("项目查询开始日期不能晚于结束日期", "AGT400");
        }
        return query;
    }

    private void enrichFromMessage(Query query, String message) {
        LocalDate today = LocalDate.now(resolveZone());
        String source = safe(message);
        String requestedOwner = AgentProjectOwnerQuery.ownerName(message);
        if (requestedOwner != null) {
            query.managerName = requestedOwner;
            query.partnerName = null;
            query.keyword = null;
            // The complete request has no creation-date or project-status constraint.
            query.startDate = null;
            query.endDate = null;
            query.statusFlag = null;
            query.businessType = null;
        }
        if (query.startDate == null && query.endDate == null) {
            Matcher dates = DATE_PATTERN.matcher(source);
            List<LocalDate> values = new ArrayList<>();
            while (dates.find() && values.size() < 2) values.add(LocalDate.parse(dates.group(1)));
            if (!values.isEmpty()) query.startDate = values.get(0);
            if (values.size() > 1) query.endDate = values.get(1);
        }
        if (query.startDate == null && containsAny(source, "今年", "本年", "本年度")) {
            query.startDate = LocalDate.of(today.getYear(), 1, 1);
            query.endDate = today;
        } else if (query.startDate == null && containsAny(source, "本月", "这个月", "当月")) {
            YearMonth month = YearMonth.from(today);
            query.startDate = month.atDay(1);
            query.endDate = today;
        } else if (query.startDate == null) {
            Matcher year = YEAR_PATTERN.matcher(source);
            if (year.find()) {
                int value = Integer.parseInt(year.group(1));
                query.startDate = LocalDate.of(value, 1, 1);
                query.endDate = LocalDate.of(value, 12, 31);
            }
        }
        boolean asksRentIn = source.contains("租入");
        boolean asksRentOut = source.contains("租出");
        if (asksRentIn && asksRentOut) {
            // The user is asking for a breakdown of the whole business-type dimension.
            // Do not let a planner-provided or message-inferred single value hide the other group.
            query.businessType = null;
        } else if (!StringUtils.hasText(query.businessType)) {
            if (asksRentIn) query.businessType = "rent_in";
            else if (asksRentOut) query.businessType = "rent_out";
        }

        boolean asksOngoing = containsAny(source, "进行中", "未完成");
        boolean asksCompleted = containsAny(source, "已完成", "已结束");
        if (asksOngoing && asksCompleted) {
            // Likewise, a status breakdown must query both mutually exclusive groups.
            query.statusFlag = null;
        } else if (!StringUtils.hasText(query.statusFlag)) {
            if (asksOngoing) query.statusFlag = "0";
            else if (asksCompleted) query.statusFlag = "1";
        }
    }

    private Map<String, Object> projectItem(ProjectEntity project) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("projectId", project.getProjectId());
        item.put("projectName", project.getProjectName());
        item.put("projectBusinessType", project.getProjectBusinessType());
        item.put("managerName", project.getManagerName());
        item.put("partnerName", project.getPartnerName());
        item.put("tenantUnit", project.getTenantUnit());
        item.put("projectStatusFlag", project.getProjectStatusFlag());
        item.put("createDate", project.getCreateDate());
        return item;
    }

    private String scopeSummary(AgentRuntimeRecords.Workspace workspace, Query query) {
        String projectScope = "ALL".equalsIgnoreCase(workspace.getSelectionMode())
                ? "本企业全部项目" : "当前选中的" + workspace.getProjectIds().size() + "个项目";
        if (StringUtils.hasText(query.managerName)) projectScope += "，我方负责人姓名包含“" + query.managerName + "”";
        if (StringUtils.hasText(query.partnerName)) projectScope += "，对方负责人姓名包含“" + query.partnerName + "”";
        if (query.startDate != null || query.endDate != null) {
            return projectScope + "，录入日期 " + (query.startDate == null ? "不限" : query.startDate)
                    + " 至 " + (query.endDate == null ? "今天" : query.endDate);
        }
        return projectScope;
    }

    private String timeRange(Query query) {
        return "createDate=" + (query.startDate == null ? "*" : query.startDate)
                + "~" + (query.endDate == null ? "*" : query.endDate);
    }

    private LocalDate parseDate(String value) {
        try { return StringUtils.hasText(value) ? LocalDate.parse(value.trim()) : null; }
        catch (Exception e) { throw new MyBizException("项目查询日期格式应为yyyy-MM-dd", "AGT400"); }
    }

    private String normalizeBusinessType(String value) {
        if (!StringUtils.hasText(value) || "ALL".equalsIgnoreCase(value)) return null;
        if ("rent_in".equalsIgnoreCase(value) || "rent_out".equalsIgnoreCase(value)) return value.toLowerCase(Locale.ROOT);
        throw new MyBizException("项目业务类型不正确", "AGT400");
    }

    private String normalizeStatus(String value) {
        if (!StringUtils.hasText(value) || "ALL".equalsIgnoreCase(value)) return null;
        if ("ONGOING".equalsIgnoreCase(value) || "0".equals(value)) return "0";
        if ("COMPLETED".equalsIgnoreCase(value) || "1".equals(value)) return "1";
        throw new MyBizException("项目状态不正确", "AGT400");
    }

    private int clampLimit(int value) { return Math.max(1, Math.min(value, MAX_LIMIT)); }
    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String dateOnly(String value) { return StringUtils.hasText(value) && value.length() >= 10 ? value.substring(0, 10) : "日期未填写"; }
    private boolean containsAny(String text, String... values) { for (String value : values) if (safe(text).contains(value)) return true; return false; }
    private String safe(String value) { return value == null ? "" : value; }

    private ZoneId resolveZone() {
        try { return ZoneId.of(runtimeTimezone); }
        catch (Exception ignored) { return ZoneId.of("Asia/Shanghai"); }
    }

    private static class Query {
        private LocalDate startDate;
        private LocalDate endDate;
        private String businessType;
        private String statusFlag;
        private String keyword;
        private String managerName;
        private String partnerName;
        private int limit = DEFAULT_LIMIT;
    }
}
