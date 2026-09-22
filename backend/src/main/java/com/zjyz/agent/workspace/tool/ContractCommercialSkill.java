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
import com.zjyz.dao.ProjectMaterialPricingMapper;
import com.zjyz.pojo.entity.ContractEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.ProjectMaterialPricingEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * D4 合同商业分析（contract.commercial_analytics）：
 * v1 覆盖两类确定性分析——合同关键字段完整性、同名称同规格同计价单位材料的跨项目日租金比较。
 * 合同价与单据执行价偏差需要逐单据比对，留待 v2，口径中显式声明。
 */
@Component
public class ContractCommercialSkill {
    public static final String TOOL_CODE = "contract.commercial_analytics";
    private static final List<String> FOCUS_VALUES = Arrays.asList("ALL", "COMPLETENESS", "PRICE_COMPARISON");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ProjectMapper projectMapper;
    private final ContractMapper contractMapper;
    private final ProjectMaterialPricingMapper pricingMapper;
    private final ObjectMapper objectMapper;

    public ContractCommercialSkill(ProjectMapper projectMapper,
                                   ContractMapper contractMapper,
                                   ProjectMaterialPricingMapper pricingMapper,
                                   ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.contractMapper = contractMapper;
        this.pricingMapper = pricingMapper;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson, String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null) {
            throw new MyBizException("工作空间上下文缺失", "AGT400");
        }
        Query query = parseQuery(argumentsJson);
        List<ProjectEntity> projects = queryProjects(workspace, query.keyword);
        Map<String, String> projectNames = projects.stream().collect(Collectors.toMap(
                ProjectEntity::getProjectId, project -> safe(project.getProjectName()), (a, b) -> a));

        List<Map<String, Object>> completenessItems = new ArrayList<>();
        int completenessTotal = 0;
        if (!"PRICE_COMPARISON".equals(query.focus)) {
            List<CompletenessItem> issues = analyzeCompleteness(projects);
            completenessTotal = issues.size();
            completenessItems = issues.stream().limit(query.limit)
                    .map(this::completenessCardItem).collect(Collectors.toList());
        }

        List<Map<String, Object>> priceItems = new ArrayList<>();
        int priceGroupTotal = 0;
        if (!"COMPLETENESS".equals(query.focus)) {
            List<PriceGroup> groups = analyzePriceGaps(workspace, projects, projectNames);
            priceGroupTotal = groups.size();
            priceItems = groups.stream().limit(query.limit)
                    .map(this::priceCardItem).collect(Collectors.toList());
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "contract-commercial-analytics");
        card.put("schemaVersion", "1.0");
        card.put("metricVersion", "contract-commercial-v1");
        card.put("asOfDate", LocalDate.now().toString());
        card.put("focus", query.focus);
        card.put("projectCount", projects.size());
        Map<String, Object> completeness = new LinkedHashMap<>();
        completeness.put("totalCount", completenessTotal);
        completeness.put("displayedCount", completenessItems.size());
        completeness.put("truncated", completenessTotal > completenessItems.size());
        completeness.put("items", completenessItems);
        card.put("completeness", completeness);
        Map<String, Object> priceComparison = new LinkedHashMap<>();
        priceComparison.put("totalCount", priceGroupTotal);
        priceComparison.put("displayedCount", priceItems.size());
        priceComparison.put("truncated", priceGroupTotal > priceItems.size());
        priceComparison.put("items", priceItems);
        card.put("priceComparison", priceComparison);
        card.put("limit", query.limit);
        card.put("scopeNote", "价格比较按材料名称+规格+计数单位分组，只比较项目定价中的日租金，不做跨单位比较；"
                + "v1 不包含合同价与单据执行价偏差核对，也不核对价格阶梯与分段计价明细。");

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + LocalDate.now());
        evidence.setSkills(Collections.singletonList("ContractCommercialSkill"));
        evidence.setApiList(Arrays.asList("internal:project", "internal:contract",
                "internal:project_material_pricing"));
        evidence.setRecordCount(completenessTotal + priceGroupTotal);

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("contract_commercial_analytics");
        execution.setConfidence(0.9d);
        execution.setAnswer(buildAnswer(workspace, query, projects.size(),
                completenessTotal, completenessItems.size(), priceGroupTotal, priceItems.size()));
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(evidence);
        return execution;
    }

    private List<ProjectEntity> queryProjects(AgentRuntimeRecords.Workspace workspace, String keyword) {
        QueryWrapper<ProjectEntity> wrapper = new QueryWrapper<ProjectEntity>().eq("cid", workspace.getCid());
        if ("EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())) {
            if (workspace.getProjectIds() == null || workspace.getProjectIds().isEmpty()) {
                throw new MyBizException("项目范围为空", "AGT400");
            }
            wrapper.in("project_id", workspace.getProjectIds());
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(item -> item.like("project_name", keyword)
                    .or().like("manager_name", keyword)
                    .or().like("partner_name", keyword));
        }
        wrapper.orderByDesc("create_date", "project_id");
        return projectMapper.selectList(wrapper);
    }

    private List<CompletenessItem> analyzeCompleteness(List<ProjectEntity> projects) {
        List<CompletenessItem> result = new ArrayList<>();
        for (ProjectEntity project : projects) {
            List<ContractEntity> contracts = contractMapper.selectList(new QueryWrapper<ContractEntity>()
                    .eq("project_id", project.getProjectId())
                    .orderByDesc("create_date", "contract_id"));
            List<String> issues = new ArrayList<>();
            ContractEntity latest = contracts.isEmpty() ? null : contracts.get(0);
            if (latest == null) {
                issues.add("NO_CONTRACT");
            } else {
                if (!StringUtils.hasText(latest.getEndDate())) {
                    issues.add("MISSING_END_DATE");
                }
                if (latest.getReconciliationPeriod() == null) {
                    issues.add("MISSING_RECONCILIATION_PERIOD");
                }
                if (latest.getTaxRate() == null) {
                    issues.add("MISSING_TAX_RATE");
                }
                if (Boolean.TRUE.equals(latest.getEnableExpandPeriodFlag())
                        && latest.getOverdueRentIncreaseRate() == null) {
                    issues.add("MISSING_OVERDUE_INCREASE_RATE");
                }
            }
            if (issues.isEmpty()) {
                continue;
            }
            CompletenessItem item = new CompletenessItem();
            item.projectId = project.getProjectId();
            item.projectName = project.getProjectName();
            item.contractName = latest == null ? null : latest.getContractName();
            item.contractEndDate = latest == null ? null : latest.getEndDate();
            item.issues = issues;
            result.add(item);
        }
        result.sort(Comparator.comparingInt((CompletenessItem item) -> item.issues.size()).reversed()
                .thenComparing(item -> safe(item.projectName)));
        return result;
    }

    private List<PriceGroup> analyzePriceGaps(AgentRuntimeRecords.Workspace workspace,
                                              List<ProjectEntity> projects,
                                              Map<String, String> projectNames) {
        if (projects.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> projectIds = projects.stream()
                .map(ProjectEntity::getProjectId).collect(Collectors.toList());
        List<ProjectMaterialPricingEntity> rows = pricingMapper.selectList(
                new QueryWrapper<ProjectMaterialPricingEntity>()
                        .eq("cid", workspace.getCid())
                        .in("project_id", projectIds)
                        .isNotNull("daily_rent"));
        Map<String, PriceGroup> groups = new LinkedHashMap<>();
        for (ProjectMaterialPricingEntity row : rows) {
            if (row.getDailyRent() == null || !StringUtils.hasText(row.getMaterialName())) {
                continue;
            }
            String key = safe(row.getMaterialName()) + "|" + safe(row.getMaterialSpecification())
                    + "|" + safe(row.getCountingUnit());
            PriceGroup group = groups.computeIfAbsent(key, ignored -> {
                PriceGroup created = new PriceGroup();
                created.materialName = row.getMaterialName();
                created.materialSpecification = row.getMaterialSpecification();
                created.countingUnit = row.getCountingUnit();
                return created;
            });
            group.projectCount++;
            if (group.minRent == null || row.getDailyRent().compareTo(group.minRent) < 0) {
                group.minRent = row.getDailyRent();
                group.minProjectName = projectNames.get(row.getProjectId());
            }
            if (group.maxRent == null || row.getDailyRent().compareTo(group.maxRent) > 0) {
                group.maxRent = row.getDailyRent();
                group.maxProjectName = projectNames.get(row.getProjectId());
            }
        }
        return groups.values().stream()
                .filter(group -> group.projectCount >= 2 && group.minRent != null
                        && group.maxRent.compareTo(group.minRent) > 0)
                .peek(group -> group.gapRatio = group.minRent.compareTo(BigDecimal.ZERO) > 0
                        ? group.maxRent.subtract(group.minRent)
                        .divide(group.minRent, 4, RoundingMode.HALF_UP)
                        : null)
                .sorted(Comparator.comparing((PriceGroup group) ->
                        group.gapRatio == null ? BigDecimal.valueOf(-1) : group.gapRatio).reversed())
                .collect(Collectors.toList());
    }

    private Map<String, Object> completenessCardItem(CompletenessItem item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("projectId", item.projectId);
        value.put("projectName", item.projectName);
        value.put("contractName", item.contractName);
        value.put("contractEndDate", item.contractEndDate);
        value.put("issues", item.issues);
        return value;
    }

    private Map<String, Object> priceCardItem(PriceGroup group) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("materialName", group.materialName);
        value.put("materialSpecification", group.materialSpecification);
        value.put("countingUnit", group.countingUnit);
        value.put("projectCount", group.projectCount);
        value.put("minDailyRent", group.minRent);
        value.put("minProjectName", group.minProjectName);
        value.put("maxDailyRent", group.maxRent);
        value.put("maxProjectName", group.maxProjectName);
        value.put("gapRatio", group.gapRatio);
        return value;
    }

    private String buildAnswer(AgentRuntimeRecords.Workspace workspace,
                               Query query,
                               int projectCount,
                               int completenessTotal,
                               int completenessDisplayed,
                               int priceGroupTotal,
                               int priceDisplayed) {
        String scope = "ALL".equalsIgnoreCase(workspace.getSelectionMode())
                ? "本企业全部项目" : "当前选中的 " + workspace.getProjectIds().size() + " 个项目";
        StringBuilder answer = new StringBuilder(scope).append("范围内共核查 ")
                .append(projectCount).append(" 个项目。");
        if (!"PRICE_COMPARISON".equals(query.focus)) {
            answer.append("合同字段完整性：").append(completenessTotal == 0
                    ? "未发现缺失关键字段的项目。"
                    : completenessTotal + " 个项目存在缺失（无合同、结束日期、结算周期、税率或超期递增率）"
                    + (completenessTotal > completenessDisplayed ? "，展示前 " + completenessDisplayed + " 个" : "") + "。");
        }
        if (!"COMPLETENESS".equals(query.focus)) {
            answer.append("同材料跨项目价差：").append(priceGroupTotal == 0
                    ? "同名称同规格同计数单位的材料未发现日租金差异。"
                    : priceGroupTotal + " 组材料在不同项目定价不一致"
                    + (priceGroupTotal > priceDisplayed ? "，展示价差最大的前 " + priceDisplayed + " 组" : "") + "。");
        }
        answer.append("v1 不含合同价与单据执行价偏差核对。");
        return answer.toString();
    }

    private Query parseQuery(String argumentsJson) {
        Query query = new Query();
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                String focus = trimToNull(root.path("focus").asText(null));
                if (focus != null) {
                    String normalized = focus.toUpperCase(Locale.ROOT);
                    if (!FOCUS_VALUES.contains(normalized)) {
                        throw new MyBizException("focus 仅支持 ALL/COMPLETENESS/PRICE_COMPARISON", "AGT400");
                    }
                    query.focus = normalized;
                }
                query.keyword = trimToNull(root.path("keyword").asText(null));
                int limit = root.path("limit").asInt(DEFAULT_LIMIT);
                query.limit = Math.max(1, Math.min(limit, MAX_LIMIT));
            } catch (MyBizException e) {
                throw e;
            } catch (Exception e) {
                throw new MyBizException("合同分析查询参数格式不正确", "AGT400");
            }
        }
        return query;
    }

    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String safe(String value) { return value == null ? "" : value; }

    private static class Query {
        private String focus = "ALL";
        private String keyword;
        private int limit = DEFAULT_LIMIT;
    }

    private static class CompletenessItem {
        private String projectId;
        private String projectName;
        private String contractName;
        private String contractEndDate;
        private List<String> issues = Collections.emptyList();
    }

    private static class PriceGroup {
        private String materialName;
        private String materialSpecification;
        private String countingUnit;
        private int projectCount;
        private BigDecimal minRent;
        private String minProjectName;
        private BigDecimal maxRent;
        private String maxProjectName;
        private BigDecimal gapRatio;
    }
}
