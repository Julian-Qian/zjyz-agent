package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.orch.AgentMaterialMetricPolicy;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.CompensationDocumentMapper;
import com.zjyz.dao.CompensationDocumentMaterialMapper;
import com.zjyz.dao.RentDocumentMapper;
import com.zjyz.dao.RentDocumentMaterialMapper;
import com.zjyz.dao.RentInDocumentMapper;
import com.zjyz.dao.RentInDocumentMaterialMapper;
import com.zjyz.dao.RentInReturnDocumentMapper;
import com.zjyz.dao.RentInReturnDocumentMaterialMapper;
import com.zjyz.dao.ReturnDocumentMapper;
import com.zjyz.dao.ReturnDocumentMaterialMapper;
import com.zjyz.pojo.entity.CompensationDocumentEntity;
import com.zjyz.pojo.entity.CompensationDocumentMaterialEntity;
import com.zjyz.pojo.entity.RentDocumentEntity;
import com.zjyz.pojo.entity.RentDocumentMaterialEntity;
import com.zjyz.pojo.entity.RentInDocumentEntity;
import com.zjyz.pojo.entity.RentInDocumentMaterialEntity;
import com.zjyz.pojo.entity.RentInReturnDocumentEntity;
import com.zjyz.pojo.entity.RentInReturnDocumentMaterialEntity;
import com.zjyz.pojo.entity.ReturnDocumentEntity;
import com.zjyz.pojo.entity.ReturnDocumentMaterialEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class MaterialTransactionAggregateSkill {
    public static final String TOOL_CODE = "material.transaction_aggregate";

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;
    private static final int DOCUMENT_BATCH_SIZE = 500;
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern YEAR_MONTH_PATTERN = Pattern.compile("(20\\d{2})[年\\-/](0?[1-9]|1[0-2])(?:月)?");
    private static final Pattern MONTH_PATTERN = Pattern.compile("(?<!\\d)(0?[1-9]|1[0-2])月份?");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})年");
    private static final Pattern TOP_PATTERN = Pattern.compile("(?:前|top\\s*)(\\d+)", Pattern.CASE_INSENSITIVE);

    private final RentDocumentMapper rentDocumentMapper;
    private final RentDocumentMaterialMapper rentMaterialMapper;
    private final ReturnDocumentMapper returnDocumentMapper;
    private final ReturnDocumentMaterialMapper returnMaterialMapper;
    private final CompensationDocumentMapper compensationDocumentMapper;
    private final CompensationDocumentMaterialMapper compensationMaterialMapper;
    private final RentInDocumentMapper rentInDocumentMapper;
    private final RentInDocumentMaterialMapper rentInMaterialMapper;
    private final RentInReturnDocumentMapper rentInReturnDocumentMapper;
    private final RentInReturnDocumentMaterialMapper rentInReturnMaterialMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.runtime.timezone:${AGENT_TIMEZONE:Asia/Shanghai}}")
    private String runtimeTimezone;

    public MaterialTransactionAggregateSkill(RentDocumentMapper rentDocumentMapper,
                                             RentDocumentMaterialMapper rentMaterialMapper,
                                             ReturnDocumentMapper returnDocumentMapper,
                                             ReturnDocumentMaterialMapper returnMaterialMapper,
                                             CompensationDocumentMapper compensationDocumentMapper,
                                             CompensationDocumentMaterialMapper compensationMaterialMapper,
                                             RentInDocumentMapper rentInDocumentMapper,
                                             RentInDocumentMaterialMapper rentInMaterialMapper,
                                             RentInReturnDocumentMapper rentInReturnDocumentMapper,
                                             RentInReturnDocumentMaterialMapper rentInReturnMaterialMapper,
                                             ObjectMapper objectMapper) {
        this.rentDocumentMapper = rentDocumentMapper;
        this.rentMaterialMapper = rentMaterialMapper;
        this.returnDocumentMapper = returnDocumentMapper;
        this.returnMaterialMapper = returnMaterialMapper;
        this.compensationDocumentMapper = compensationDocumentMapper;
        this.compensationMaterialMapper = compensationMaterialMapper;
        this.rentInDocumentMapper = rentInDocumentMapper;
        this.rentInMaterialMapper = rentInMaterialMapper;
        this.rentInReturnDocumentMapper = rentInReturnDocumentMapper;
        this.rentInReturnMaterialMapper = rentInReturnMaterialMapper;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson,
                                       String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (workspace == null || !StringUtils.hasText(workspace.getCid())) {
            throw new MyBizException("工作空间上下文缺失", "AGT400");
        }
        if (AgentMaterialMetricPolicy.incompatibleWithQuantity(originalMessage)) {
            throw new MyBizException("材料流水排行只支持数量和单据次数，不能回答赚钱、收入、回款或利润；请先明确金额口径并核实是否有对应能力。",
                    "AGT_METRIC_MISMATCH");
        }
        Query query = parseQuery(argumentsJson, originalMessage);
        List<MaterialEvent> events = loadEvents(workspace, query);
        List<Aggregate> aggregates = aggregate(events, query);
        Comparator<Aggregate> comparator = "DOCUMENT_COUNT".equals(query.metric)
                ? Comparator.comparingInt(Aggregate::documentCount)
                : Comparator.comparing(Aggregate::totalQuantity);
        if ("DESC".equals(query.order)) comparator = comparator.reversed();
        comparator = comparator.thenComparing(value -> safe(value.materialName))
                .thenComparing(value -> safe(value.materialSpecification));

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> unitGroups = new ArrayList<>();
        Map<String, List<Aggregate>> aggregatesByUnit = aggregates.stream()
                .collect(Collectors.groupingBy(value -> value.materialUnit, LinkedHashMap::new, Collectors.toList()));
        List<String> orderedUnits = new ArrayList<>(aggregatesByUnit.keySet());
        orderedUnits.sort(String::compareTo);
        for (String unit : orderedUnits) {
            List<Aggregate> unitAggregates = aggregatesByUnit.get(unit);
            unitAggregates.sort(comparator);
            List<Map<String, Object>> unitItems = new ArrayList<>();
            for (int index = 0; index < Math.min(query.limit, unitAggregates.size()); index++) {
                Map<String, Object> item = toItem(unitAggregates.get(index), index + 1);
                unitItems.add(item);
                items.add(item);
            }
            Map<String, Object> unitGroup = new LinkedHashMap<>();
            unitGroup.put("unit", unit);
            unitGroup.put("unitMissing", "未标注单位".equals(unit));
            unitGroup.put("totalCount", unitAggregates.size());
            unitGroup.put("displayedCount", unitItems.size());
            unitGroup.put("truncated", unitItems.size() < unitAggregates.size());
            unitGroup.put("totalQuantity", decimalValue(unitAggregates.stream()
                    .map(value -> value.totalQuantity).reduce(BigDecimal.ZERO, BigDecimal::add)));
            unitGroup.put("items", unitItems);
            unitGroups.add(unitGroup);
        }
        Set<String> documentIds = aggregates.stream().flatMap(value -> value.documentIds.stream()).collect(Collectors.toSet());
        Set<String> projectIds = aggregates.stream().flatMap(value -> value.projectIds.stream()).collect(Collectors.toSet());
        boolean heterogeneousUnits = unitGroups.size() > 1;
        boolean missingUnit = aggregatesByUnit.containsKey("未标注单位");
        String aggregationWarning = heterogeneousUnits
                ? "存在多种计数单位，不同单位不可直接相加或互相排名；结果已按单位分别汇总。"
                + (missingUnit ? " 其中未标注单位的数据已单独列出，请补齐单位后再比较。" : "")
                : missingUnit ? "存在未标注计数单位的数据，已单独列出且未按“件”等单位推定。" : null;

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "material-transaction-ranking");
        card.put("schemaVersion", "1.2");
        card.put("flowType", query.flowType);
        card.put("flowLabel", flowLabel(query.flowType));
        card.put("startDate", query.startDate == null ? null : query.startDate.toString());
        card.put("endDate", query.endDate == null ? null : query.endDate.toString());
        card.put("groupBy", query.groupBy);
        card.put("metric", query.metric);
        card.put("order", query.order);
        card.put("reviewScope", query.reviewScope);
        card.put("limit", query.limit);
        card.put("materialGroupCount", aggregates.size());
        card.put("totalCount", aggregates.size());
        card.put("displayedCount", items.size());
        card.put("truncated", items.size() < aggregates.size());
        card.put("documentCount", documentIds.size());
        card.put("projectCount", projectIds.size());
        card.put("unitCount", unitGroups.size());
        card.put("hasMissingUnit", missingUnit);
        card.put("totalQuantity", heterogeneousUnits || unitGroups.isEmpty()
                ? null : unitGroups.get(0).get("totalQuantity"));
        card.put("unitGroups", unitGroups);
        card.put("aggregationWarning", aggregationWarning);
        card.put("items", heterogeneousUnits ? Collections.emptyList() : items);
        card.put("scopeNote", scopeNote(workspace, query)
                + (aggregationWarning == null ? "" : " " + aggregationWarning));

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange(timeRange(query));
        evidence.setSkills(Collections.singletonList("MaterialTransactionAggregateSkill"));
        evidence.setApiList(Collections.singletonList("internal:" + materialTable(query.flowType)));
        evidence.setRecordCount(events.size());

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("material_transaction_aggregate");
        execution.setConfidence(0.98d);
        execution.setAnswer(buildAnswer(query, aggregates, unitGroups,
                documentIds.size(), projectIds.size(), aggregationWarning));
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(evidence);
        return execution;
    }

    private List<MaterialEvent> loadEvents(AgentRuntimeRecords.Workspace workspace, Query query) {
        switch (query.flowType) {
            case "RETURN":
                return loadReturnEvents(workspace, query);
            case "COMPENSATION":
                return loadCompensationEvents(workspace, query);
            case "RENT_IN":
                return loadRentInEvents(workspace, query);
            case "RENT_IN_RETURN":
                return loadRentInReturnEvents(workspace, query);
            case "RENT_OUT":
            default:
                return loadRentOutEvents(workspace, query);
        }
    }

    private List<MaterialEvent> loadRentOutEvents(AgentRuntimeRecords.Workspace workspace, Query query) {
        List<RentDocumentEntity> documents = rentDocumentMapper.selectList(documentScope(workspace, query, "rent_date"));
        return materialEvents(workspace.getCid(), documents, rentMaterialMapper,
                RentDocumentMaterialEntity::getDocumentId,
                RentDocumentEntity::getRentDocumentId, RentDocumentEntity::getProjectId, RentDocumentEntity::getRentDate,
                RentDocumentMaterialEntity::getMaterialName, RentDocumentMaterialEntity::getMaterialSpecification,
                value -> firstText(value.getCountingUnit(), value.getMaterialUnit()),
                value -> quantity(value.getCountingQuantity(), value.getMaterialNumber()),
                RentDocumentMaterialEntity::getMaterialBusinessType, true);
    }

    private List<MaterialEvent> loadReturnEvents(AgentRuntimeRecords.Workspace workspace, Query query) {
        List<ReturnDocumentEntity> documents = returnDocumentMapper.selectList(documentScope(workspace, query, "return_date"));
        return materialEvents(workspace.getCid(), documents, returnMaterialMapper,
                ReturnDocumentMaterialEntity::getDocumentId,
                ReturnDocumentEntity::getReturnDocumentId, ReturnDocumentEntity::getProjectId, ReturnDocumentEntity::getReturnDate,
                ReturnDocumentMaterialEntity::getMaterialName, ReturnDocumentMaterialEntity::getMaterialSpecification,
                value -> firstText(value.getCountingUnit(), value.getMaterialUnit()),
                value -> quantity(value.getCountingQuantity(), value.getMaterialNumber()),
                ReturnDocumentMaterialEntity::getMaterialBusinessType, true);
    }

    private List<MaterialEvent> loadCompensationEvents(AgentRuntimeRecords.Workspace workspace, Query query) {
        List<CompensationDocumentEntity> documents = compensationDocumentMapper.selectList(documentScope(workspace, query, "compensation_date"));
        return materialEvents(workspace.getCid(), documents, compensationMaterialMapper,
                CompensationDocumentMaterialEntity::getDocumentId,
                CompensationDocumentEntity::getCompensationDocumentId, CompensationDocumentEntity::getProjectId,
                CompensationDocumentEntity::getCompensationDate,
                CompensationDocumentMaterialEntity::getMaterialName, CompensationDocumentMaterialEntity::getMaterialSpecification,
                CompensationDocumentMaterialEntity::getMaterialUnit,
                value -> quantity(null, value.getMaterialNumber()), value -> null, false);
    }

    private List<MaterialEvent> loadRentInEvents(AgentRuntimeRecords.Workspace workspace, Query query) {
        List<RentInDocumentEntity> documents = rentInDocumentMapper.selectList(documentScope(workspace, query, "rent_in_date"));
        return materialEvents(workspace.getCid(), documents, rentInMaterialMapper,
                RentInDocumentMaterialEntity::getDocumentId,
                RentInDocumentEntity::getRentInDocumentId, RentInDocumentEntity::getProjectId, RentInDocumentEntity::getRentInDate,
                RentInDocumentMaterialEntity::getMaterialName, RentInDocumentMaterialEntity::getMaterialSpecification,
                value -> firstText(value.getCountingUnit(), value.getMaterialUnit()),
                value -> quantity(value.getCountingQuantity(), value.getMaterialNumber()), value -> null, false);
    }

    private List<MaterialEvent> loadRentInReturnEvents(AgentRuntimeRecords.Workspace workspace, Query query) {
        List<RentInReturnDocumentEntity> documents = rentInReturnDocumentMapper.selectList(documentScope(workspace, query, "rent_in_return_date"));
        return materialEvents(workspace.getCid(), documents, rentInReturnMaterialMapper,
                RentInReturnDocumentMaterialEntity::getDocumentId,
                RentInReturnDocumentEntity::getRentInReturnDocumentId, RentInReturnDocumentEntity::getProjectId,
                RentInReturnDocumentEntity::getRentInReturnDate,
                RentInReturnDocumentMaterialEntity::getMaterialName, RentInReturnDocumentMaterialEntity::getMaterialSpecification,
                value -> firstText(value.getCountingUnit(), value.getMaterialUnit()),
                value -> quantity(value.getCountingQuantity(), value.getMaterialNumber()), value -> null, false);
    }

    private <T> QueryWrapper<T> documentScope(AgentRuntimeRecords.Workspace workspace, Query query, String dateColumn) {
        QueryWrapper<T> wrapper = new QueryWrapper<T>().eq("cid", workspace.getCid())
                .isNotNull(dateColumn).ne(dateColumn, "");
        if ("EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())) {
            if (workspace.getProjectIds() == null || workspace.getProjectIds().isEmpty()) {
                throw new MyBizException("项目范围为空", "AGT400");
            }
            wrapper.in("project_id", workspace.getProjectIds());
        }
        if (query.startDate != null) wrapper.ge(dateColumn, query.startDate.toString());
        if (query.endDate != null) wrapper.lt(dateColumn, query.endDate.plusDays(1).toString());
        if ("REVIEWED".equals(query.reviewScope)) {
            wrapper.eq("review_status", 1);
        } else if ("UNREVIEWED".equals(query.reviewScope)) {
            wrapper.and(value -> value.eq("review_status", 0).or().isNull("review_status"));
        }
        return wrapper;
    }

    private <D, M> List<MaterialEvent> materialEvents(String cid,
                                                       List<D> documents,
                                                       BaseMapper<M> materialMapper,
                                                       Function<M, String> materialDocumentId,
                                                       Function<D, String> documentId,
                                                       Function<D, String> projectId,
                                                       Function<D, String> documentDate,
                                                       Function<M, String> materialName,
                                                       Function<M, String> specification,
                                                       Function<M, String> unit,
                                                       Function<M, BigDecimal> quantity,
                                                       Function<M, String> materialBusinessType,
                                                       boolean excludeBuyout) {
        if (documents == null || documents.isEmpty()) return Collections.emptyList();
        Map<String, DocumentMeta> documentById = new LinkedHashMap<>();
        for (D document : documents) {
            String id = documentId.apply(document);
            if (StringUtils.hasText(id)) {
                documentById.put(id, new DocumentMeta(projectId.apply(document), documentDate.apply(document)));
            }
        }
        List<String> documentIds = new ArrayList<>(documentById.keySet());
        List<MaterialEvent> result = new ArrayList<>();
        for (int start = 0; start < documentIds.size(); start += DOCUMENT_BATCH_SIZE) {
            List<String> batch = documentIds.subList(start, Math.min(start + DOCUMENT_BATCH_SIZE, documentIds.size()));
            List<M> materials = materialMapper.selectList(new QueryWrapper<M>().eq("cid", cid).in("document_id", batch));
            for (M material : materials) {
                if (excludeBuyout && "BUYOUT".equalsIgnoreCase(materialBusinessType.apply(material))) continue;
                String documentKey = materialDocumentId.apply(material);
                DocumentMeta meta = documentById.get(documentKey);
                if (meta == null) continue;
                BigDecimal amount = quantity.apply(material);
                if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) continue;
                result.add(new MaterialEvent(documentKey, meta.projectId, meta.documentDate,
                        materialName.apply(material), specification.apply(material), unit.apply(material), amount));
            }
        }
        return result;
    }

    private List<Aggregate> aggregate(List<MaterialEvent> events, Query query) {
        Map<String, Aggregate> grouped = new LinkedHashMap<>();
        String keyword = safe(query.materialKeyword).toLowerCase(Locale.ROOT);
        for (MaterialEvent event : events) {
            String name = firstText(event.materialName, "未命名材料");
            String specification = safe(event.materialSpecification);
            String unit = firstText(event.materialUnit, "未标注单位");
            if (StringUtils.hasText(keyword)
                    && !(name + " " + specification).toLowerCase(Locale.ROOT).contains(keyword)) continue;
            String key = name + "\u0000" + unit;
            if ("MATERIAL_SPEC".equals(query.groupBy)) key += "\u0000" + specification;
            Aggregate value = grouped.computeIfAbsent(key, ignored -> new Aggregate(name,
                    "MATERIAL_SPEC".equals(query.groupBy) ? specification : null, unit));
            value.totalQuantity = value.totalQuantity.add(event.quantity);
            value.documentIds.add(event.documentId);
            value.projectIds.add(event.projectId);
            value.specifications.add(specification);
        }
        return new ArrayList<>(grouped.values());
    }

    private Map<String, Object> toItem(Aggregate value, int rank) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("rank", rank);
        item.put("materialName", value.materialName);
        item.put("materialSpecification", value.materialSpecification);
        item.put("materialUnit", value.materialUnit);
        item.put("specificationCount", value.specifications.size());
        item.put("totalQuantity", decimalValue(value.totalQuantity));
        item.put("quantity", decimalValue(value.totalQuantity));
        item.put("documentCount", value.documentCount());
        item.put("projectCount", value.projectIds.size());
        return item;
    }

    private Query parseQuery(String argumentsJson, String originalMessage) {
        Query query = new Query();
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                query.flowType = normalizeFlowType(root.path("flowType").asText(null));
                query.startDate = parseDate(root.path("startDate").asText(null));
                query.endDate = parseDate(root.path("endDate").asText(null));
                query.groupBy = normalizeEnum(root.path("groupBy").asText(null), "MATERIAL_NAME", "MATERIAL_NAME", "MATERIAL_SPEC");
                query.metric = normalizeEnum(root.path("metric").asText(null), "QUANTITY", "QUANTITY", "DOCUMENT_COUNT");
                query.order = normalizeEnum(root.path("order").asText(null), "DESC", "ASC", "DESC");
                query.reviewScope = normalizeEnum(root.path("reviewScope").asText(null), "ALL", "ALL", "REVIEWED", "UNREVIEWED");
                query.materialKeyword = trimToNull(root.path("materialKeyword").asText(null));
                query.limit = clamp(root.path("limit").asInt(DEFAULT_LIMIT));
            } catch (MyBizException e) {
                throw e;
            } catch (Exception e) {
                throw new MyBizException("材料流水聚合参数格式不正确", "AGT400");
            }
        }
        enrichFromMessage(query, originalMessage);
        if (query.startDate != null && query.endDate != null && query.startDate.isAfter(query.endDate)) {
            throw new MyBizException("统计开始日期不能晚于结束日期", "AGT400");
        }
        return query;
    }

    private void enrichFromMessage(Query query, String message) {
        String source = safe(message);
        query.directTopQuestion = containsAny(source, "最多", "最少", "最高", "最低")
                && containsAny(source, "哪个", "哪种", "哪一种", "是什么", "是哪一个");
        query.periodLabel = inferPeriodLabel(source);
        LocalDate today = LocalDate.now(resolveZone());
        if (!StringUtils.hasText(query.flowType)) query.flowType = inferFlowType(source);
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
            query.startDate = YearMonth.from(today).atDay(1);
            query.endDate = today;
        } else if (query.startDate == null) {
            Matcher yearMonth = YEAR_MONTH_PATTERN.matcher(source);
            if (yearMonth.find()) {
                YearMonth value = YearMonth.of(Integer.parseInt(yearMonth.group(1)), Integer.parseInt(yearMonth.group(2)));
                query.startDate = value.atDay(1);
                query.endDate = value.atEndOfMonth();
            } else {
                Matcher month = MONTH_PATTERN.matcher(source);
                if (month.find()) {
                    YearMonth value = YearMonth.of(today.getYear(), Integer.parseInt(month.group(1)));
                    query.startDate = value.atDay(1);
                    query.endDate = value.atEndOfMonth();
                } else {
                    Matcher year = YEAR_PATTERN.matcher(source);
                    if (year.find()) {
                        int value = Integer.parseInt(year.group(1));
                        query.startDate = LocalDate.of(value, 1, 1);
                        query.endDate = LocalDate.of(value, 12, 31);
                    }
                }
            }
        }
        if (containsAny(source, "按规格", "各规格", "分规格")) query.groupBy = "MATERIAL_SPEC";
        else if (containsAny(source, "按材料名称", "跨规格", "同名材料")) query.groupBy = "MATERIAL_NAME";
        if (containsAny(source, "次数", "频次", "单据数")) query.metric = "DOCUMENT_COUNT";
        if (containsAny(source, "最少", "最低")) query.order = "ASC";
        if (source.contains("已复核")) query.reviewScope = "REVIEWED";
        else if (source.contains("未复核")) query.reviewScope = "UNREVIEWED";
        Matcher top = TOP_PATTERN.matcher(source);
        if (top.find()) query.limit = clamp(Integer.parseInt(top.group(1)));
    }

    private String inferFlowType(String source) {
        if (containsAny(source, "退租", "租入归还")) return "RENT_IN_RETURN";
        if (source.contains("租入")) return "RENT_IN";
        if (source.contains("赔偿")) return "COMPENSATION";
        if (source.contains("归还")) return "RETURN";
        return "RENT_OUT";
    }

    private String normalizeFlowType(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (Arrays.asList("RENT_OUT", "RETURN", "COMPENSATION", "RENT_IN", "RENT_IN_RETURN").contains(normalized)) {
            return normalized;
        }
        throw new MyBizException("材料业务流水类型不正确", "AGT400");
    }

    private String normalizeEnum(String value, String defaultValue, String... allowed) {
        if (!StringUtils.hasText(value)) return defaultValue;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (Arrays.asList(allowed).contains(normalized)) return normalized;
        throw new MyBizException("材料流水聚合筛选值不正确", "AGT400");
    }

    private String buildAnswer(Query query,
                               List<Aggregate> aggregates,
                               List<Map<String, Object>> unitGroups,
                               int documentCount,
                               int projectCount,
                               String aggregationWarning) {
        String range = humanRange(query);
        if (aggregates.isEmpty()) {
            return naturalPeriodPrefix(query, range) + "没有查到符合条件的" + flowLabel(query.flowType) + "材料记录。";
        }
        String period = naturalPeriodPrefix(query, range);
        if (query.directTopQuestion) {
            if (unitGroups.size() == 1) {
                Map<String, Object> group = unitGroups.get(0);
                Map<String, Object> leader = firstItem(group);
                return period + flowLabel(query.flowType) + metricLeaderLabel(query) + "是"
                        + leaderText(query, group, leader) + "。";
            }
            List<Map<String, Object>> groups = new ArrayList<>(unitGroups);
            groups.sort(Comparator.comparingInt(group -> presentationUnitPriority(safe((String) group.get("unit")))));
            List<String> leaders = groups.stream().limit(4)
                    .map(group -> safe((String) group.get("unit")) + "：" + leaderText(query, group, firstItem(group)))
                    .collect(Collectors.toList());
            String remaining = groups.size() > leaders.size()
                    ? "；其余 " + (groups.size() - leaders.size()) + " 种单位见结果卡" : "";
            return period + flowLabel(query.flowType) + "材料使用了多种计量单位，不能直接选出唯一第一名。"
                    + "按各自单位看，" + String.join("；", leaders) + remaining + "。";
        }
        String unitSummary = unitGroups.size() > 1
                ? "结果已按 " + unitGroups.size() + " 种计量单位分别排序，明细见结果卡；不同单位不能互相比较。"
                : "结果已按" + metricLabel(query.metric) + ("ASC".equals(query.order) ? "从少到多" : "从多到少")
                + "排列，明细见结果卡。";
        return period + "共统计 " + projectCount + " 个项目、" + documentCount + " 张"
                + flowLabel(query.flowType) + "单。" + unitSummary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstItem(Map<String, Object> unitGroup) {
        List<Map<String, Object>> items = unitGroup.get("items") instanceof List
                ? (List<Map<String, Object>>) unitGroup.get("items") : Collections.emptyList();
        return items.isEmpty() ? Collections.emptyMap() : items.get(0);
    }

    private String leaderText(Query query, Map<String, Object> group, Map<String, Object> item) {
        String name = safe(String.valueOf(item.get("materialName")));
        String specification = StringUtils.hasText((String) item.get("materialSpecification"))
                ? "（" + item.get("materialSpecification") + "）" : "";
        Object value = "DOCUMENT_COUNT".equals(query.metric) ? item.get("documentCount") : item.get("totalQuantity");
        String valueUnit = "DOCUMENT_COUNT".equals(query.metric)
                ? "张单据" : safe((String) group.get("unit"));
        return name + specification + "（" + value + valueUnit + "）";
    }

    private String metricLeaderLabel(Query query) {
        String direction = "ASC".equals(query.order) ? "最少" : "最多";
        return "DOCUMENT_COUNT".equals(query.metric) ? "涉及单据" + direction + "的材料" : "数量" + direction + "的材料";
    }

    private String inferPeriodLabel(String source) {
        if (containsAny(source, "今年", "本年", "本年度")) return "今年";
        if (containsAny(source, "本月", "这个月", "当月")) return "本月";
        Matcher yearMonth = YEAR_MONTH_PATTERN.matcher(source);
        if (yearMonth.find()) return Integer.parseInt(yearMonth.group(1)) + "年" + Integer.parseInt(yearMonth.group(2)) + "月";
        Matcher year = YEAR_PATTERN.matcher(source);
        if (year.find()) return Integer.parseInt(year.group(1)) + "年";
        return null;
    }

    private String naturalPeriodPrefix(Query query, String fallbackRange) {
        return StringUtils.hasText(query.periodLabel) ? query.periodLabel + "，" : fallbackRange;
    }

    private int presentationUnitPriority(String unit) {
        List<String> preferred = Arrays.asList("根", "吨", "只", "米", "件", "个", "套", "张", "片", "桶", "未标注单位");
        int index = preferred.indexOf(unit);
        return index < 0 ? preferred.size() : index;
    }

    private String scopeNote(AgentRuntimeRecords.Workspace workspace, Query query) {
        String projectScope = "ALL".equalsIgnoreCase(workspace.getSelectionMode())
                ? "本企业全部项目" : "当前选中的" + workspace.getProjectIds().size() + "个项目";
        String grouping = "MATERIAL_NAME".equals(query.groupBy)
                ? "同名且同计数单位材料跨规格合计" : "按材料名称、规格和计数单位分别统计";
        return projectScope + "；按单据业务日期统计" + flowLabel(query.flowType) + "流水；" + grouping
                + ("DOCUMENT_COUNT".equals(query.metric) ? "；单据数也按计数单位分组展示，不作为跨单位材料总榜" : "")
                + "；数量优先使用计数数量，缺失时使用材料数量；复核范围=" + reviewScopeLabel(query.reviewScope)
                + (Arrays.asList("RENT_OUT", "RETURN").contains(query.flowType) ? "；买断材料不计入租赁流水" : "") + "。";
    }

    private String timeRange(Query query) {
        return "businessDate=" + (query.startDate == null ? "*" : query.startDate)
                + "~" + (query.endDate == null ? "*" : query.endDate)
                + ",flowType=" + query.flowType + ",reviewScope=" + query.reviewScope;
    }

    private String humanRange(Query query) {
        if (query.startDate == null && query.endDate == null) return "全部日期内，";
        return (query.startDate == null ? "不限" : query.startDate) + " 至 "
                + (query.endDate == null ? "今天" : query.endDate) + "，";
    }

    private String flowLabel(String flowType) {
        switch (flowType) {
            case "RETURN": return "归还";
            case "COMPENSATION": return "赔偿";
            case "RENT_IN": return "租入";
            case "RENT_IN_RETURN": return "退租";
            case "RENT_OUT":
            default: return "租出";
        }
    }

    private String metricLabel(String metric) {
        return "DOCUMENT_COUNT".equals(metric) ? "涉及单据数" : "材料数量";
    }

    private String materialTable(String flowType) {
        switch (flowType) {
            case "RETURN": return "return_document_material";
            case "COMPENSATION": return "compensation_document_material";
            case "RENT_IN": return "rent_in_document_material";
            case "RENT_IN_RETURN": return "rent_in_return_document_material";
            case "RENT_OUT":
            default: return "rent_document_material";
        }
    }

    private String reviewScopeLabel(String reviewScope) {
        if ("REVIEWED".equals(reviewScope)) return "仅已复核";
        if ("UNREVIEWED".equals(reviewScope)) return "仅未复核";
        return "全部已保存单据";
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            throw new MyBizException("统计日期格式应为yyyy-MM-dd", "AGT400");
        }
    }

    private ZoneId resolveZone() {
        try {
            return ZoneId.of(runtimeTimezone);
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private BigDecimal quantity(Double countingQuantity, Integer materialNumber) {
        if (countingQuantity != null) return BigDecimal.valueOf(countingQuantity);
        return materialNumber == null ? BigDecimal.ZERO : BigDecimal.valueOf(materialNumber.longValue());
    }

    private Object decimalValue(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value.stripTrailingZeros();
        return normalized.scale() <= 0 ? normalized.longValue() : normalized;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) if (safe(text).contains(keyword)) return true;
        return false;
    }

    private String firstText(String... values) {
        for (String value : values) if (StringUtils.hasText(value)) return value.trim();
        return "";
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(value <= 0 ? DEFAULT_LIMIT : value, MAX_LIMIT));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static class Query {
        private String flowType;
        private LocalDate startDate;
        private LocalDate endDate;
        private String groupBy = "MATERIAL_NAME";
        private String metric = "QUANTITY";
        private String order = "DESC";
        private String reviewScope = "ALL";
        private String materialKeyword;
        private int limit = DEFAULT_LIMIT;
        private String periodLabel;
        private boolean directTopQuestion;
    }

    private static class DocumentMeta {
        private final String projectId;
        private final String documentDate;

        private DocumentMeta(String projectId, String documentDate) {
            this.projectId = projectId;
            this.documentDate = documentDate;
        }
    }

    private static class MaterialEvent {
        private final String documentId;
        private final String projectId;
        private final String documentDate;
        private final String materialName;
        private final String materialSpecification;
        private final String materialUnit;
        private final BigDecimal quantity;

        private MaterialEvent(String documentId, String projectId, String documentDate,
                              String materialName, String materialSpecification, String materialUnit,
                              BigDecimal quantity) {
            this.documentId = documentId;
            this.projectId = projectId;
            this.documentDate = documentDate;
            this.materialName = materialName;
            this.materialSpecification = materialSpecification;
            this.materialUnit = materialUnit;
            this.quantity = quantity;
        }
    }

    private static class Aggregate {
        private final String materialName;
        private final String materialSpecification;
        private final String materialUnit;
        private BigDecimal totalQuantity = BigDecimal.ZERO;
        private final Set<String> documentIds = new HashSet<>();
        private final Set<String> projectIds = new HashSet<>();
        private final Set<String> specifications = new LinkedHashSet<>();

        private Aggregate(String materialName, String materialSpecification, String materialUnit) {
            this.materialName = materialName;
            this.materialSpecification = materialSpecification;
            this.materialUnit = materialUnit;
        }

        private BigDecimal totalQuantity() { return totalQuantity; }
        private int documentCount() { return documentIds.size(); }
    }
}
