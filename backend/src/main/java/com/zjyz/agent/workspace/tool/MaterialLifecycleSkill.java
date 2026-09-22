package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.CompensationDocumentMaterialMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.RentDocumentMapper;
import com.zjyz.dao.RentDocumentMaterialMapper;
import com.zjyz.dao.ReturnDocumentMapper;
import com.zjyz.dao.ReturnDocumentMaterialMapper;
import com.zjyz.pojo.entity.CompensationDocumentMaterialEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.RentDocumentEntity;
import com.zjyz.pojo.entity.RentDocumentMaterialEntity;
import com.zjyz.pojo.entity.ReturnDocumentEntity;
import com.zjyz.pojo.entity.ReturnDocumentMaterialEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * D5 材料生命周期分析（material.lifecycle_analytics）：v1 覆盖租出方向的三类确定性分析——
 * 多还（归还+赔偿 &gt; 租出，保留负差额原值）、长期只租不还（有未归还且长期无归还流水）、
 * 赔偿率（赔偿数量/租出数量，按名称+规格+计数单位分组）。
 * 租龄按"项目最近一次归还距今天数"近似，FIFO 逐笔租龄留待 v2；租入方向暂不纳入。
 */
@Component
public class MaterialLifecycleSkill {
    public static final String TOOL_CODE = "material.lifecycle_analytics";
    private static final List<String> ANALYSIS_TYPES = Arrays.asList(
            "ALL", "OVER_RETURN", "STALE_OCCUPANCY", "COMPENSATION_RATE");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_STAGNANT_DAYS = 60;

    private final ProjectMapper projectMapper;
    private final RentDocumentMaterialMapper rentMaterialMapper;
    private final ReturnDocumentMaterialMapper returnMaterialMapper;
    private final CompensationDocumentMaterialMapper compensationMaterialMapper;
    private final RentDocumentMapper rentDocumentMapper;
    private final ReturnDocumentMapper returnDocumentMapper;
    private final ObjectMapper objectMapper;

    public MaterialLifecycleSkill(ProjectMapper projectMapper,
                                  RentDocumentMaterialMapper rentMaterialMapper,
                                  ReturnDocumentMaterialMapper returnMaterialMapper,
                                  CompensationDocumentMaterialMapper compensationMaterialMapper,
                                  RentDocumentMapper rentDocumentMapper,
                                  ReturnDocumentMapper returnDocumentMapper,
                                  ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.rentMaterialMapper = rentMaterialMapper;
        this.returnMaterialMapper = returnMaterialMapper;
        this.compensationMaterialMapper = compensationMaterialMapper;
        this.rentDocumentMapper = rentDocumentMapper;
        this.returnDocumentMapper = returnDocumentMapper;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson, String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null) {
            throw new MyBizException("工作空间上下文缺失", "AGT400");
        }
        Query query = parseQuery(argumentsJson);
        List<ProjectEntity> projects = queryRentOutProjects(workspace);
        Map<String, ProjectEntity> projectById = projects.stream()
                .collect(Collectors.toMap(ProjectEntity::getProjectId, project -> project, (a, b) -> a));
        List<String> projectIds = new ArrayList<>(projectById.keySet());
        Map<String, Balance> balances = loadBalances(workspace.getCid(), projectIds);

        List<Map<String, Object>> overReturnItems = new ArrayList<>();
        int overReturnTotal = 0;
        if (include(query, "OVER_RETURN")) {
            List<Balance> overReturns = balances.values().stream()
                    .filter(balance -> balance.outstanding() < 0)
                    .sorted(Comparator.comparingInt(Balance::outstanding))
                    .collect(Collectors.toList());
            overReturnTotal = overReturns.size();
            overReturnItems = overReturns.stream().limit(query.limit)
                    .map(balance -> balanceItem(balance, projectById)).collect(Collectors.toList());
        }

        List<Map<String, Object>> staleItems = new ArrayList<>();
        int staleTotal = 0;
        if (include(query, "STALE_OCCUPANCY")) {
            List<StaleProject> stale = analyzeStaleOccupancy(workspace.getCid(), projectById, balances, query);
            staleTotal = stale.size();
            staleItems = stale.stream().limit(query.limit)
                    .map(this::staleItem).collect(Collectors.toList());
        }

        List<Map<String, Object>> compensationItems = new ArrayList<>();
        int compensationTotal = 0;
        if (include(query, "COMPENSATION_RATE")) {
            List<CompensationRate> rates = analyzeCompensationRates(balances);
            compensationTotal = rates.size();
            compensationItems = rates.stream().limit(query.limit)
                    .map(this::compensationItem).collect(Collectors.toList());
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "material-lifecycle-analytics");
        card.put("schemaVersion", "1.0");
        card.put("metricVersion", "material-lifecycle-v1");
        card.put("asOfDate", LocalDate.now().toString());
        card.put("analysisType", query.analysisType);
        card.put("minStagnantDays", query.minStagnantDays);
        card.put("overReturn", section(overReturnTotal, overReturnItems));
        card.put("staleOccupancy", section(staleTotal, staleItems));
        card.put("compensationRate", section(compensationTotal, compensationItems));
        card.put("limit", query.limit);
        card.put("scopeNote", "仅覆盖租出方向；数量按材料名称+规格+计数单位分组，不做跨单位合计；"
                + "多还保留负差额原值；租龄按项目最近一次归还距今天数近似，逐笔 FIFO 租龄留待 v2。");

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + LocalDate.now()
                + (include(query, "STALE_OCCUPANCY") ? ", minStagnantDays=" + query.minStagnantDays : ""));
        evidence.setSkills(Collections.singletonList("MaterialLifecycleSkill"));
        evidence.setApiList(Arrays.asList("internal:rent_document_material", "internal:return_document_material",
                "internal:compensation_document_material", "internal:rent_document", "internal:return_document"));
        evidence.setRecordCount(overReturnTotal + staleTotal + compensationTotal);

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("material_lifecycle_analytics");
        execution.setConfidence(0.9d);
        execution.setAnswer(buildAnswer(workspace, query, overReturnTotal, staleTotal, compensationTotal));
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(evidence);
        return execution;
    }

    private List<ProjectEntity> queryRentOutProjects(AgentRuntimeRecords.Workspace workspace) {
        QueryWrapper<ProjectEntity> wrapper = new QueryWrapper<ProjectEntity>()
                .eq("cid", workspace.getCid())
                .ne("project_business_type", "rent_in");
        if ("EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())) {
            if (workspace.getProjectIds() == null || workspace.getProjectIds().isEmpty()) {
                throw new MyBizException("项目范围为空", "AGT400");
            }
            wrapper.in("project_id", workspace.getProjectIds());
        }
        return projectMapper.selectList(wrapper);
    }

    private Map<String, Balance> loadBalances(String cid, List<String> projectIds) {
        Map<String, Balance> result = new LinkedHashMap<>();
        if (projectIds.isEmpty()) {
            return result;
        }
        for (RentDocumentMaterialEntity value : rentMaterialMapper.selectList(
                new QueryWrapper<RentDocumentMaterialEntity>().eq("cid", cid).in("project_id", projectIds))) {
            if ("BUYOUT".equalsIgnoreCase(value.getMaterialBusinessType())) {
                continue;
            }
            balance(result, value.getProjectId(), value.getMaterialName(),
                    value.getMaterialSpecification(), value.getMaterialUnit()).rented += safeInt(value.getMaterialNumber());
        }
        for (ReturnDocumentMaterialEntity value : returnMaterialMapper.selectList(
                new QueryWrapper<ReturnDocumentMaterialEntity>().eq("cid", cid).in("project_id", projectIds))) {
            if ("BUYOUT".equalsIgnoreCase(value.getMaterialBusinessType())) {
                continue;
            }
            balance(result, value.getProjectId(), value.getMaterialName(),
                    value.getMaterialSpecification(), value.getMaterialUnit()).returned += safeInt(value.getMaterialNumber());
        }
        for (CompensationDocumentMaterialEntity value : compensationMaterialMapper.selectList(
                new QueryWrapper<CompensationDocumentMaterialEntity>().eq("cid", cid).in("project_id", projectIds))) {
            balance(result, value.getProjectId(), value.getMaterialName(),
                    value.getMaterialSpecification(), value.getMaterialUnit()).compensated += safeInt(value.getMaterialNumber());
        }
        return result;
    }

    private Balance balance(Map<String, Balance> balances,
                            String projectId,
                            String name,
                            String specification,
                            String unit) {
        String key = safe(projectId) + " " + safe(name) + " " + safe(specification) + " " + safe(unit);
        Balance value = balances.computeIfAbsent(key, ignored -> new Balance());
        value.projectId = projectId;
        if (!StringUtils.hasText(value.materialName)) {
            value.materialName = name;
        }
        if (!StringUtils.hasText(value.specification)) {
            value.specification = specification;
        }
        if (!StringUtils.hasText(value.unit)) {
            value.unit = unit;
        }
        return value;
    }

    private List<StaleProject> analyzeStaleOccupancy(String cid,
                                                     Map<String, ProjectEntity> projectById,
                                                     Map<String, Balance> balances,
                                                     Query query) {
        Map<String, Integer> outstandingGroupsByProject = new LinkedHashMap<>();
        for (Balance balance : balances.values()) {
            if (balance.outstanding() > 0) {
                outstandingGroupsByProject.merge(balance.projectId, 1, Integer::sum);
            }
        }
        LocalDate today = LocalDate.now();
        List<StaleProject> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : outstandingGroupsByProject.entrySet()) {
            ProjectEntity project = projectById.get(entry.getKey());
            if (project == null) {
                continue;
            }
            LocalDate lastReturn = latestDate(returnDocumentMapper.selectList(
                    new QueryWrapper<ReturnDocumentEntity>().eq("cid", cid)
                            .eq("project_id", entry.getKey())
                            .orderByDesc("return_date").last("LIMIT 1")).stream()
                    .map(ReturnDocumentEntity::getReturnDate).findFirst().orElse(null));
            LocalDate lastRent = latestDate(rentDocumentMapper.selectList(
                    new QueryWrapper<RentDocumentEntity>().eq("cid", cid)
                            .eq("project_id", entry.getKey())
                            .orderByDesc("rent_date").last("LIMIT 1")).stream()
                    .map(RentDocumentEntity::getRentDate).findFirst().orElse(null));
            LocalDate baseline = lastReturn != null ? lastReturn : lastRent;
            long staleDays = baseline == null ? -1L : ChronoUnit.DAYS.between(baseline, today);
            if (baseline != null && staleDays < query.minStagnantDays) {
                continue;
            }
            StaleProject stale = new StaleProject();
            stale.projectId = entry.getKey();
            stale.projectName = project.getProjectName();
            stale.managerName = project.getManagerName();
            stale.outstandingMaterialGroups = entry.getValue();
            stale.lastReturnDate = lastReturn;
            stale.lastRentDate = lastRent;
            stale.staleDays = baseline == null ? null : staleDays;
            result.add(stale);
        }
        result.sort(Comparator.comparing((StaleProject item) ->
                item.staleDays == null ? Long.MAX_VALUE : item.staleDays).reversed());
        return result;
    }

    private List<CompensationRate> analyzeCompensationRates(Map<String, Balance> balances) {
        Map<String, CompensationRate> groups = new LinkedHashMap<>();
        for (Balance balance : balances.values()) {
            String key = safe(balance.materialName) + "|" + safe(balance.specification) + "|" + safe(balance.unit);
            CompensationRate group = groups.computeIfAbsent(key, ignored -> {
                CompensationRate created = new CompensationRate();
                created.materialName = balance.materialName;
                created.specification = balance.specification;
                created.unit = balance.unit;
                return created;
            });
            group.rented += balance.rented;
            group.compensated += balance.compensated;
        }
        return groups.values().stream()
                .filter(group -> group.rented > 0 && group.compensated > 0)
                .peek(group -> group.rate = BigDecimal.valueOf(group.compensated)
                        .divide(BigDecimal.valueOf(group.rented), 4, RoundingMode.HALF_UP))
                .sorted(Comparator.comparing((CompensationRate group) -> group.rate).reversed())
                .collect(Collectors.toList());
    }

    private Map<String, Object> section(int totalCount, List<Map<String, Object>> items) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("totalCount", totalCount);
        value.put("displayedCount", items.size());
        value.put("truncated", totalCount > items.size());
        value.put("items", items);
        return value;
    }

    private Map<String, Object> balanceItem(Balance balance, Map<String, ProjectEntity> projectById) {
        ProjectEntity project = projectById.get(balance.projectId);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("projectId", balance.projectId);
        item.put("projectName", project == null ? null : project.getProjectName());
        item.put("materialName", balance.materialName);
        item.put("materialSpecification", balance.specification);
        item.put("materialUnit", balance.unit);
        item.put("rentedQuantity", balance.rented);
        item.put("returnedQuantity", balance.returned);
        item.put("compensatedQuantity", balance.compensated);
        item.put("outstandingQuantity", balance.outstanding());
        return item;
    }

    private Map<String, Object> staleItem(StaleProject stale) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("projectId", stale.projectId);
        item.put("projectName", stale.projectName);
        item.put("managerName", stale.managerName);
        item.put("outstandingMaterialGroups", stale.outstandingMaterialGroups);
        item.put("lastReturnDate", stale.lastReturnDate == null ? null : stale.lastReturnDate.toString());
        item.put("lastRentDate", stale.lastRentDate == null ? null : stale.lastRentDate.toString());
        item.put("staleDays", stale.staleDays);
        return item;
    }

    private Map<String, Object> compensationItem(CompensationRate group) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("materialName", group.materialName);
        item.put("materialSpecification", group.specification);
        item.put("materialUnit", group.unit);
        item.put("rentedQuantity", group.rented);
        item.put("compensatedQuantity", group.compensated);
        item.put("compensationRate", group.rate);
        return item;
    }

    private String buildAnswer(AgentRuntimeRecords.Workspace workspace,
                               Query query,
                               int overReturnTotal,
                               int staleTotal,
                               int compensationTotal) {
        String scope = "ALL".equalsIgnoreCase(workspace.getSelectionMode())
                ? "本企业全部租出项目" : "当前选中范围内的租出项目";
        StringBuilder answer = new StringBuilder(scope).append("生命周期核查：");
        if (include(query, "OVER_RETURN")) {
            answer.append("多还材料组 ").append(overReturnTotal).append(" 组；");
        }
        if (include(query, "STALE_OCCUPANCY")) {
            answer.append("有未归还且超过 ").append(query.minStagnantDays)
                    .append(" 天无归还流水的项目 ").append(staleTotal).append(" 个；");
        }
        if (include(query, "COMPENSATION_RATE")) {
            answer.append("发生过赔偿的材料组 ").append(compensationTotal).append(" 组（按赔偿率排序）；");
        }
        answer.append("明细见结果卡。数量按名称+规格+计数单位分组，未做跨单位合计；仅覆盖租出方向。");
        return answer.toString();
    }

    private boolean include(Query query, String section) {
        return "ALL".equals(query.analysisType) || section.equals(query.analysisType);
    }

    private LocalDate latestDate(String value) {
        try {
            return StringUtils.hasText(value) && value.length() >= 10
                    ? LocalDate.parse(value.substring(0, 10)) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Query parseQuery(String argumentsJson) {
        Query query = new Query();
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                String type = trimToNull(root.path("analysisType").asText(null));
                if (type != null) {
                    String normalized = type.toUpperCase(Locale.ROOT);
                    if (!ANALYSIS_TYPES.contains(normalized)) {
                        throw new MyBizException("analysisType 仅支持 ALL/OVER_RETURN/STALE_OCCUPANCY/COMPENSATION_RATE", "AGT400");
                    }
                    query.analysisType = normalized;
                }
                int stagnantDays = root.path("minStagnantDays").asInt(DEFAULT_STAGNANT_DAYS);
                query.minStagnantDays = Math.max(1, Math.min(stagnantDays, 3650));
                int limit = root.path("limit").asInt(DEFAULT_LIMIT);
                query.limit = Math.max(1, Math.min(limit, MAX_LIMIT));
            } catch (MyBizException e) {
                throw e;
            } catch (Exception e) {
                throw new MyBizException("材料生命周期查询参数格式不正确", "AGT400");
            }
        }
        return query;
    }

    private int safeInt(Integer value) { return value == null ? 0 : value; }
    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String safe(String value) { return value == null ? "" : value; }

    private static class Query {
        private String analysisType = "ALL";
        private int minStagnantDays = DEFAULT_STAGNANT_DAYS;
        private int limit = DEFAULT_LIMIT;
    }

    private static class Balance {
        private String projectId;
        private String materialName;
        private String specification;
        private String unit;
        private int rented;
        private int returned;
        private int compensated;

        private int outstanding() {
            return rented - returned - compensated;
        }
    }

    private static class StaleProject {
        private String projectId;
        private String projectName;
        private String managerName;
        private int outstandingMaterialGroups;
        private LocalDate lastReturnDate;
        private LocalDate lastRentDate;
        private Long staleDays;
    }

    private static class CompensationRate {
        private String materialName;
        private String specification;
        private String unit;
        private int rented;
        private int compensated;
        private BigDecimal rate;
    }
}
