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
import com.zjyz.pojo.entity.ContractEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ProjectContractStatusSkill {
    public static final String TOOL_CODE = "project.contract_status";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final Pattern YEAR_MONTH = Pattern.compile("(20\\d{2})[年\\-/](0?[1-9]|1[0-2])(?:月)?");

    private final ProjectMapper projectMapper;
    private final ContractMapper contractMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.runtime.timezone:${AGENT_TIMEZONE:Asia/Shanghai}}")
    private String runtimeTimezone;

    public ProjectContractStatusSkill(ProjectMapper projectMapper,
                                      ContractMapper contractMapper,
                                      ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.contractMapper = contractMapper;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson,
                                       String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        Query query = parseQuery(argumentsJson, originalMessage);
        List<ProjectEntity> projects = queryProjects(workspace, query);
        Map<String, ContractEntity> latestContracts = latestContracts(projects);
        List<Map<String, Object>> allItems = new ArrayList<>();
        for (ProjectEntity project : projects) {
            ContractEntity contract = latestContracts.get(project.getProjectId());
            String status = deriveStatus(contract, query.asOfDate);
            if (!matchesExpiryRange(contract, query)) {
                continue;
            }
            if (!"ALL".equals(query.status) && !query.status.equals(status)) {
                continue;
            }
            if (StringUtils.hasText(query.keyword) && !matchesKeyword(project, contract, query.keyword)) {
                continue;
            }
            allItems.add(item(project, contract, status));
        }
        allItems.sort(Comparator
                .comparing((Map<String, Object> value) -> safe((String) value.get("endDate")), Comparator.nullsLast(String::compareTo))
                .thenComparing(value -> safe((String) value.get("projectName"))));
        List<Map<String, Object>> displayed = allItems.stream().limit(query.limit).collect(Collectors.toList());

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "project-contract-status-list");
        card.put("schemaVersion", "1.0");
        card.put("asOfDate", query.asOfDate.toString());
        card.put("expiryStartDate", dateText(query.expiryStartDate));
        card.put("expiryEndDate", dateText(query.expiryEndDate));
        card.put("status", query.status);
        card.put("total", allItems.size());
        card.put("displayedCount", displayed.size());
        card.put("items", displayed);
        card.put("scopeNote", "每个项目取最近录入合同；区间查询按合同结束日期筛选，状态按截止日期派生，不代表合同法律效力判断。");

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange(query.hasExpiryRange()
                ? "contractEndDate=" + query.expiryStartDate + "~" + query.expiryEndDate
                : "asOf=" + query.asOfDate);
        evidence.setSkills(Collections.singletonList("ProjectContractStatusSkill"));
        evidence.setApiList(java.util.Arrays.asList("internal:project", "internal:contract"));
        evidence.setRecordCount(allItems.size());

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("project_contract_status");
        execution.setConfidence(0.96d);
        execution.setAnswer(buildAnswer(query, allItems));
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(evidence);
        return execution;
    }

    private List<ProjectEntity> queryProjects(AgentRuntimeRecords.Workspace workspace, Query query) {
        if (workspace == null) throw new MyBizException("工作空间上下文缺失", "AGT400");
        QueryWrapper<ProjectEntity> wrapper = new QueryWrapper<ProjectEntity>().eq("cid", workspace.getCid());
        if ("EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())) {
            if (workspace.getProjectIds() == null || workspace.getProjectIds().isEmpty()) {
                throw new MyBizException("项目范围为空", "AGT400");
            }
            wrapper.in("project_id", workspace.getProjectIds());
        }
        if (StringUtils.hasText(query.projectStatusFlag)) {
            wrapper.eq("project_status_flag", query.projectStatusFlag);
        }
        return projectMapper.selectList(wrapper);
    }

    private Map<String, ContractEntity> latestContracts(List<ProjectEntity> projects) {
        Map<String, ContractEntity> result = new LinkedHashMap<>();
        if (projects.isEmpty()) return result;
        List<String> projectIds = projects.stream().map(ProjectEntity::getProjectId).collect(Collectors.toList());
        List<ContractEntity> contracts = contractMapper.selectList(new QueryWrapper<ContractEntity>()
                .in("project_id", projectIds)
                .orderByDesc("create_date", "contract_id"));
        for (ContractEntity contract : contracts) {
            result.putIfAbsent(contract.getProjectId(), contract);
        }
        return result;
    }

    private Query parseQuery(String argumentsJson, String message) {
        Query query = new Query();
        query.asOfDate = LocalDate.now(resolveZone());
        query.status = containsAny(message, "到期", "过期", "失效") ? "EXPIRED" : "ALL";
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                query.asOfDate = parseDate(root.path("asOfDate").asText(null), query.asOfDate);
                query.status = normalizeStatus(root.path("status").asText(query.status));
                query.expiryStartDate = parseDate(root.path("expiryStartDate").asText(null), query.expiryStartDate);
                query.expiryEndDate = parseDate(root.path("expiryEndDate").asText(null), query.expiryEndDate);
                query.projectStatusFlag = normalizeProjectStatus(root.path("projectStatus").asText(null));
                query.keyword = trimToNull(root.path("keyword").asText(null));
                query.limit = clamp(root.path("limit").asInt(DEFAULT_LIMIT));
            } catch (MyBizException e) {
                throw e;
            } catch (Exception e) {
                throw new MyBizException("合同状态查询参数格式不正确", "AGT400");
            }
        }
        if (!query.hasExpiryRange()) {
            resolveExpiryRangeFromMessage(query, message);
        }
        if (query.hasExpiryRange()) {
            if (query.expiryStartDate == null || query.expiryEndDate == null) {
                throw new MyBizException("合同到期区间必须同时提供开始和结束日期", "AGT400");
            }
            if (query.expiryStartDate.isAfter(query.expiryEndDate)) {
                throw new MyBizException("合同到期区间开始日期不能晚于结束日期", "AGT400");
            }
            // 区间查询描述的是结束日期落在期间内，不再叠加“截至今天已过期”的状态条件。
            query.status = "ALL";
        }
        if (!StringUtils.hasText(query.projectStatusFlag)) {
            if (containsAny(message, "进行中", "未完成")) query.projectStatusFlag = "0";
            else if (containsAny(message, "已完成", "已结束")) query.projectStatusFlag = "1";
        }
        return query;
    }

    private String deriveStatus(ContractEntity contract, LocalDate asOfDate) {
        if (contract == null) return "NO_CONTRACT";
        LocalDate start = parseDate(contract.getStartDate(), null);
        LocalDate end = parseDate(contract.getEndDate(), null);
        if (start != null && start.isAfter(asOfDate)) return "NOT_STARTED";
        if (end == null) return "NO_END_DATE";
        return end.isBefore(asOfDate) ? "EXPIRED" : "ACTIVE";
    }

    private boolean matchesExpiryRange(ContractEntity contract, Query query) {
        if (!query.hasExpiryRange()) return true;
        if (contract == null) return false;
        LocalDate end = parseDate(contract.getEndDate(), null);
        return end != null && !end.isBefore(query.expiryStartDate) && !end.isAfter(query.expiryEndDate);
    }

    private void resolveExpiryRangeFromMessage(Query query, String message) {
        if (!containsAny(message, "到期", "过期", "失效")) return;
        LocalDate today = query.asOfDate;
        YearMonth month = null;
        if (containsAny(message, "本月", "这个月", "当月")) {
            month = YearMonth.from(today);
        } else if (StringUtils.hasText(message)) {
            Matcher matcher = YEAR_MONTH.matcher(message);
            if (matcher.find()) {
                month = YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            }
        }
        if (month != null) {
            query.expiryStartDate = month.atDay(1);
            query.expiryEndDate = month.atEndOfMonth();
        }
    }

    private Map<String, Object> item(ProjectEntity project, ContractEntity contract, String status) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("projectId", project.getProjectId());
        value.put("projectName", project.getProjectName());
        value.put("projectStatusFlag", project.getProjectStatusFlag());
        value.put("projectBusinessType", project.getProjectBusinessType());
        value.put("managerName", project.getManagerName());
        value.put("customerName", firstText(project.getTenantUnit(), project.getPartnerName(), contract == null ? null : contract.getCustomerName()));
        value.put("contractId", contract == null ? null : contract.getContractId());
        value.put("contractName", contract == null ? null : contract.getContractName());
        value.put("startDate", contract == null ? null : dateOnly(contract.getStartDate()));
        value.put("endDate", contract == null ? null : dateOnly(contract.getEndDate()));
        value.put("contractStatus", status);
        return value;
    }

    private String buildAnswer(Query query, List<Map<String, Object>> items) {
        String subject = query.hasExpiryRange() ? expiryPeriodLabel(query) : "截至" + chineseDate(query.asOfDate);
        if (items.isEmpty()) {
            return query.hasExpiryRange() ? subject + "没有合同到期。" : subject + "没有符合条件的合同。";
        }
        String preview = items.stream().limit(8)
                .map(value -> contractPreview(value, query.asOfDate))
                .collect(Collectors.joining("、"));
        String remaining = items.size() > 8 ? "等，共" + items.size() + "个，完整名单见结果卡" : "";
        if (query.hasExpiryRange()) {
            return subject + "有" + items.size() + "个合同到期：" + preview + remaining + "。";
        }
        return subject + "，有" + items.size() + "个符合条件的合同：" + preview + remaining + "。";
    }

    private String expiryPeriodLabel(Query query) {
        if (query.expiryStartDate != null && query.expiryEndDate != null
                && YearMonth.from(query.expiryStartDate).equals(YearMonth.from(query.expiryEndDate))
                && query.expiryStartDate.equals(YearMonth.from(query.expiryStartDate).atDay(1))
                && query.expiryEndDate.equals(YearMonth.from(query.expiryEndDate).atEndOfMonth())) {
            return query.expiryStartDate.getYear() + "年" + query.expiryStartDate.getMonthValue() + "月";
        }
        return chineseDate(query.expiryStartDate) + "至" + chineseDate(query.expiryEndDate);
    }

    private String contractPreview(Map<String, Object> item, LocalDate asOfDate) {
        String projectName = safe((String) item.get("projectName"));
        LocalDate endDate = parseDate((String) item.get("endDate"), null);
        if (endDate == null) {
            return projectName + "（未填写到期日）";
        }
        String date = endDate.getMonthValue() + "月" + endDate.getDayOfMonth() + "日";
        if (endDate.isBefore(asOfDate)) {
            return projectName + "（" + date + "，已到期）";
        }
        if (endDate.equals(asOfDate)) {
            return projectName + "（今天到期）";
        }
        return projectName + "（" + date + "到期）";
    }

    private String chineseDate(LocalDate date) {
        return date == null ? "未指定日期" : date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    private boolean matchesKeyword(ProjectEntity project, ContractEntity contract, String keyword) {
        return containsIgnoreCase(project.getProjectName(), keyword)
                || containsIgnoreCase(project.getManagerName(), keyword)
                || containsIgnoreCase(project.getTenantUnit(), keyword)
                || containsIgnoreCase(project.getPartnerName(), keyword)
                || (contract != null && (containsIgnoreCase(contract.getContractName(), keyword)
                || containsIgnoreCase(contract.getCustomerName(), keyword)));
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        try { return LocalDate.parse(value.trim().substring(0, 10)); }
        catch (Exception e) { if (fallback != null) throw new MyBizException("日期格式应为yyyy-MM-dd", "AGT400"); return null; }
    }

    private String normalizeStatus(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "ALL";
        if (java.util.Arrays.asList("ALL", "EXPIRED", "ACTIVE", "NOT_STARTED", "NO_END_DATE", "NO_CONTRACT").contains(normalized)) return normalized;
        throw new MyBizException("合同状态筛选值不正确", "AGT400");
    }

    private String normalizeProjectStatus(String value) {
        if (!StringUtils.hasText(value) || "ALL".equalsIgnoreCase(value)) return null;
        if ("ONGOING".equalsIgnoreCase(value) || "0".equals(value)) return "0";
        if ("COMPLETED".equalsIgnoreCase(value) || "1".equals(value)) return "1";
        throw new MyBizException("项目状态筛选值不正确", "AGT400");
    }

    private boolean containsAny(String text, String... keywords) { for (String keyword : keywords) if (safe(text).contains(keyword)) return true; return false; }
    private boolean containsIgnoreCase(String text, String keyword) { return safe(text).toLowerCase(Locale.ROOT).contains(safe(keyword).toLowerCase(Locale.ROOT)); }
    private String firstText(String... values) { for (String value : values) if (StringUtils.hasText(value)) return value; return null; }
    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String dateOnly(String value) { return StringUtils.hasText(value) && value.length() >= 10 ? value.substring(0, 10) : value; }
    private String dateText(LocalDate value) { return value == null ? null : value.toString(); }
    private int clamp(int value) { return Math.max(1, Math.min(value, MAX_LIMIT)); }
    private String safe(String value) { return value == null ? "" : value; }
    private ZoneId resolveZone() { try { return ZoneId.of(runtimeTimezone); } catch (Exception e) { return ZoneId.of("Asia/Shanghai"); } }

    private static class Query {
        private LocalDate asOfDate;
        private String status;
        private LocalDate expiryStartDate;
        private LocalDate expiryEndDate;
        private String projectStatusFlag;
        private String keyword;
        private int limit = DEFAULT_LIMIT;

        private boolean hasExpiryRange() {
            return expiryStartDate != null || expiryEndDate != null;
        }
    }
}
