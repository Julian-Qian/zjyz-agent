package com.zjyz.agent.orch.skill;

import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentIntentType;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.orch.AgentSkillHandler;
import com.zjyz.agent.orch.AgentSlotBag;
import com.zjyz.pojo.param.req.CreateMaterialEstimateParam;
import com.zjyz.pojo.param.ret.MaterialEstimateDetailRet;
import com.zjyz.pojo.param.ret.MaterialEstimateItemRet;
import com.zjyz.pojo.param.ret.MaterialEstimateMatchRet;
import com.zjyz.pojo.param.ret.ParseMaterialEstimateTextRet;
import com.zjyz.service.MaterialEstimateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MaterialEstimateSkillHandler implements AgentSkillHandler {

    @Autowired
    private MaterialEstimateService materialEstimateService;

    @Override
    public AgentIntentType supportedIntent() {
        return AgentIntentType.MATERIAL_ESTIMATE;
    }

    @Override
    public AgentSkillExecution execute(String message, Map<String, Object> context, AgentSlotBag slots) {
        String mergedMessage = mergeWithMemory(message, context);
        ParseMaterialEstimateTextRet parsed = materialEstimateService.parseNlpInput(mergedMessage);
        CreateMaterialEstimateParam param = mergeParams(slots, parsed, mergedMessage);
        List<String> missingFields = collectMissingFields(param);
        if (!missingFields.isEmpty()) {
            return AgentSkillExecution.clarification(
                    "material_estimate",
                    buildClarificationQuestion(missingFields),
                    missingFields,
                    buildEvidence(0)
            );
        }

        try {
            MaterialEstimateDetailRet detail = materialEstimateService.createEstimate(param, "agent");
            AgentSkillExecution execution = new AgentSkillExecution();
            execution.setIntent("material_estimate");
            execution.setConfidence(0.93);
            execution.setAnswer(buildNarrative(detail));
            execution.setCards(buildCards(detail));
            execution.setWarnings(detail.getWarnings());
            execution.setEvidence(buildEvidence(detail.getItems() == null ? 0 : detail.getItems().size()));
            return execution;
        } catch (Exception e) {
            AgentSkillExecution execution = new AgentSkillExecution();
            execution.setIntent("material_estimate");
            execution.setConfidence(0.78);
            execution.setAnswer("材料预估暂时未能完成，原因是：" + e.getMessage());
            execution.setWarnings(Collections.singletonList("已跳过预估流程，请检查系数初始化和输入参数。"));
            execution.setEvidence(buildEvidence(0));
            return execution;
        }
    }

    private String mergeWithMemory(String message, Map<String, Object> context) {
        String current = message == null ? "" : message.trim();
        String lastUserMessage = context == null ? null : stringValue(context.get("memoryLastUserMessage"));
        String lastIntent = context == null ? null : stringValue(context.get("memoryLastIntent"));
        if (!"material_estimate".equalsIgnoreCase(lastIntent) || !StringUtils.hasText(lastUserMessage)) {
            return current;
        }
        if (StringUtils.hasText(current) && lastUserMessage.contains(current)) {
            return lastUserMessage;
        }
        return lastUserMessage + "；" + current;
    }

    private CreateMaterialEstimateParam mergeParams(AgentSlotBag slots,
                                                    ParseMaterialEstimateTextRet parsed,
                                                    String rawMessage) {
        CreateMaterialEstimateParam param = new CreateMaterialEstimateParam();
        param.setProjectName(firstNonBlank(
                slots == null ? null : slots.getEstimateProjectName(),
                parsed == null ? null : parsed.getProjectName(),
                "AI材料预估"));
        param.setBuildingArea(firstNonNull(
                toDecimal(slots == null ? null : slots.getBuildingArea()),
                parsed == null ? null : parsed.getBuildingArea()));
        param.setFloorAbove(firstNonNull(
                slots == null ? null : slots.getFloorAbove(),
                parsed == null ? null : parsed.getFloorAbove()));
        param.setFloorBelow(firstNonNull(
                slots == null ? null : slots.getFloorBelow(),
                parsed == null ? null : parsed.getFloorBelow(),
                0));
        param.setBuildingType(firstNonBlank(
                slots == null ? null : slots.getBuildingType(),
                parsed == null ? null : parsed.getBuildingType()));
        param.setStructureType(firstNonBlank(
                slots == null ? null : slots.getStructureType(),
                parsed == null ? null : parsed.getStructureType()));
        param.setWallPerimeter(firstNonNull(
                toDecimal(slots == null ? null : slots.getWallPerimeter()),
                parsed == null ? null : parsed.getWallPerimeter()));
        param.setBuildingHeight(firstNonNull(
                toDecimal(slots == null ? null : slots.getBuildingHeight()),
                parsed == null ? null : parsed.getBuildingHeight()));
        param.setRentalDays(firstNonNull(
                slots == null ? null : slots.getRentalDays(),
                parsed == null ? null : parsed.getRentalDays()));
        param.setRegion(firstNonBlank(
                slots == null ? null : slots.getRegion(),
                parsed == null ? null : parsed.getRegion()));
        param.setSpecialReq(firstNonBlank(
                slots == null ? null : slots.getSpecialRequirement(),
                parsed == null ? null : parsed.getSpecialReq()));
        param.setNlpRawInput(rawMessage);
        return param;
    }

    private List<String> collectMissingFields(CreateMaterialEstimateParam param) {
        List<String> fields = new ArrayList<>();
        if (param.getBuildingArea() == null || param.getBuildingArea().compareTo(BigDecimal.ZERO) <= 0) {
            fields.add("buildingArea");
        }
        if (param.getFloorAbove() == null || param.getFloorAbove() <= 0) {
            fields.add("floorAbove");
        }
        if (!StringUtils.hasText(param.getBuildingType())) {
            fields.add("buildingType");
        }
        if (!StringUtils.hasText(param.getStructureType())) {
            fields.add("structureType");
        }
        if (param.getWallPerimeter() == null || param.getWallPerimeter().compareTo(BigDecimal.ZERO) <= 0) {
            fields.add("wallPerimeter");
        }
        if (param.getBuildingHeight() == null || param.getBuildingHeight().compareTo(BigDecimal.ZERO) <= 0) {
            fields.add("buildingHeight");
        }
        if (param.getRentalDays() == null || param.getRentalDays() <= 0) {
            fields.add("rentalDays");
        }
        return fields;
    }

    private String buildClarificationQuestion(List<String> missingFields) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("buildingArea", "建筑面积");
        labels.put("floorAbove", "地上层数");
        labels.put("buildingType", "建筑类型");
        labels.put("structureType", "结构类型");
        labels.put("wallPerimeter", "外墙周长");
        labels.put("buildingHeight", "建筑高度");
        labels.put("rentalDays", "租赁天数");
        String text = missingFields.stream()
                .map(field -> labels.getOrDefault(field, field))
                .collect(Collectors.joining("、"));
        return "继续完成材料预估还缺这些信息：" + text + "。你可以直接回复，例如：3.5万方、28层住宅、框剪结构、外墙周长320米、高84米、租180天。";
    }

    private String buildNarrative(MaterialEstimateDetailRet detail) {
        MaterialEstimateMatchRet match = detail.getInventoryMatch();
        StringBuilder answer = new StringBuilder();
        answer.append("已完成“")
                .append(defaultString(detail.getProjectName()))
                .append("”的材料预估。外墙面积约 ")
                .append(toString(detail.getWallArea()))
                .append("，共输出 ")
                .append(detail.getItems() == null ? 0 : detail.getItems().size())
                .append(" 类材料。");
        if (StringUtils.hasText(detail.getScopeSummary())) {
            answer.append(" ").append(detail.getScopeSummary());
        }
        if (match != null) {
            answer.append(" 库存足量 ")
                    .append(defaultInteger(match.getEnoughCount(), 0))
                    .append(" 项，不足 ")
                    .append(defaultInteger(match.getShortageCount(), 0))
                    .append(" 项。接单建议：")
                    .append(suggestionLabel(detail.getSuggestion()))
                    .append("。");
            if (!CollectionUtils.isEmpty(match.getShortageItems())) {
                String shortageText = match.getShortageItems().stream()
                        .limit(3)
                        .map(item -> defaultString(item.getMaterialName()) + "(" + toString(item.getGapQty()) + ")")
                        .collect(Collectors.joining("、"));
                answer.append(" 主要缺口：").append(shortageText).append("。");
            }
        }
        if (Boolean.TRUE.equals(detail.getAiApplied()) && StringUtils.hasText(detail.getAiSummary())) {
            answer.append(" AI修正说明：").append(detail.getAiSummary()).append("。");
        }
        return answer.toString();
    }

    private List<Map<String, Object>> buildCards(MaterialEstimateDetailRet detail) {
        List<Map<String, Object>> cards = new ArrayList<>();

        Map<String, Object> estimateSummary = new LinkedHashMap<>();
        estimateSummary.put("type", "estimate-summary");
        estimateSummary.put("estimateId", detail.getEstimateId());
        estimateSummary.put("projectName", detail.getProjectName());
        estimateSummary.put("wallArea", detail.getWallArea());
        estimateSummary.put("suggestion", detail.getSuggestion());
        estimateSummary.put("aiApplied", detail.getAiApplied());
        estimateSummary.put("aiSummary", detail.getAiSummary());
        estimateSummary.put("scopeSummary", detail.getScopeSummary());
        estimateSummary.put("scopeNotices", detail.getScopeNotices());
        estimateSummary.put("items", detail.getItems() == null
                ? Collections.emptyList()
                : detail.getItems().stream().limit(6).map(item -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("materialName", item.getMaterialName());
                    map.put("materialSpecification", item.getMaterialSpecification());
                    map.put("unit", item.getUnit());
                    map.put("estimatedQty", item.getEstimatedQty());
                    return map;
                }).collect(Collectors.toList()));
        cards.add(estimateSummary);

        MaterialEstimateMatchRet match = detail.getInventoryMatch();
        if (match != null) {
            Map<String, Object> inventoryMatch = new LinkedHashMap<>();
            inventoryMatch.put("type", "inventory-match");
            inventoryMatch.put("estimateId", detail.getEstimateId());
            inventoryMatch.put("suggestion", detail.getSuggestion());
            inventoryMatch.put("enoughCount", match.getEnoughCount());
            inventoryMatch.put("shortageCount", match.getShortageCount());
            inventoryMatch.put("totalGapQty", match.getTotalGapQty());
            inventoryMatch.put("shortageItems", match.getShortageItems() == null
                    ? Collections.emptyList()
                    : match.getShortageItems().stream().limit(5).map(item -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("materialName", item.getMaterialName());
                        map.put("materialSpecification", item.getMaterialSpecification());
                        map.put("gapQty", item.getGapQty());
                        map.put("unit", item.getUnit());
                        return map;
                    }).collect(Collectors.toList()));
            cards.add(inventoryMatch);
        }
        return cards;
    }

    private AgentEvidence buildEvidence(int recordCount) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + LocalDate.now());
        evidence.setSkills(Arrays.asList("MaterialEstimateSkill", "InventorySnapshotSkill"));
        evidence.setApiList(Arrays.asList("/materialEstimate/create", "/document/query-inventory", "/agent/chat"));
        evidence.setRecordCount(recordCount);
        return evidence;
    }

    private String suggestionLabel(String suggestion) {
        if ("accept".equalsIgnoreCase(suggestion)) {
            return "可接单";
        }
        if ("reject".equalsIgnoreCase(suggestion)) {
            return "不建议接单";
        }
        return "补货后可接";
    }

    private BigDecimal toDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private Integer defaultInteger(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String toString(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
