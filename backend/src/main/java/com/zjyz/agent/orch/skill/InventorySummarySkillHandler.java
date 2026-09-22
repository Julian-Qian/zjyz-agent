package com.zjyz.agent.orch.skill;

import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.*;
import com.zjyz.pojo.param.ret.MaterialInventoryDetailRet;
import com.zjyz.pojo.param.ret.MaterialInventoryRet;
import com.zjyz.service.InventoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class InventorySummarySkillHandler implements AgentSkillHandler {

    @Autowired
    private InventoryService inventoryService;

    @Override
    public AgentIntentType supportedIntent() {
        return AgentIntentType.INVENTORY_SUMMARY;
    }

    @Override
    public AgentSkillExecution execute(String message, Map<String, Object> context, AgentSlotBag slots) {
        List<MaterialInventoryRet> inventoryList = inventoryService.queryInventory();
        if (CollectionUtils.isEmpty(inventoryList)) {
            AgentSkillExecution execution = new AgentSkillExecution();
            execution.setIntent("inventory_snapshot");
            execution.setConfidence(0.88);
            execution.setAnswer("当前未查询到库存数据。");
            execution.setEvidence(buildEvidence(0));
            return execution;
        }

        List<MaterialInventoryRet> lowStock = new ArrayList<>(inventoryList);
        lowStock.sort(Comparator.comparingInt(item -> item.getInventoryQuantity() == null ? Integer.MAX_VALUE : item.getInventoryQuantity()));
        List<MaterialInventoryRet> lowStockTop = lowStock.stream().limit(5).collect(Collectors.toList());

        String lowStockText = lowStockTop.stream()
                .map(item -> item.getMaterialName() + "(" + safeInt(item.getInventoryQuantity()) + ")")
                .collect(Collectors.joining("、"));

        StringBuilder answer = new StringBuilder("当前库存材料共 ")
                .append(inventoryList.size())
                .append(" 种。库存较低材料示例：")
                .append(lowStockText)
                .append("。");

        List<String> warnings = new ArrayList<>();
        if (slots != null && slots.isTrendRequested()) {
            List<InventoryDropInfo> drops = calculateInventoryDrops(inventoryList);
            if (CollectionUtils.isEmpty(drops)) {
                warnings.add("未找到可计算6个月趋势的材料流水数据。");
            } else {
                String dropText = drops.stream()
                        .limit(3)
                        .map(item -> item.materialName + "(" + item.delta + ")")
                        .collect(Collectors.joining("、"));
                answer.append(" 近6个月下降较快材料：").append(dropText).append("。");
            }
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "inventory-summary");
        card.put("materialCount", inventoryList.size());
        card.put("lowStockTop", lowStockTop);

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("inventory_summary");
        execution.setConfidence(0.89);
        execution.setAnswer(answer.toString());
        execution.setCards(Collections.singletonList(card));
        execution.setWarnings(warnings);
        execution.setEvidence(buildEvidence(inventoryList.size()));
        return execution;
    }

    private AgentEvidence buildEvidence(int recordCount) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("range=" + LocalDate.now().minusMonths(6) + "~" + LocalDate.now());
        evidence.setSkills(Collections.singletonList("InventoryTrendSkill"));
        evidence.setApiList(Arrays.asList("/document/query-inventory", "/document/query-inventory-detail"));
        evidence.setRecordCount(recordCount);
        return evidence;
    }

    private List<InventoryDropInfo> calculateInventoryDrops(List<MaterialInventoryRet> inventoryList) {
        LocalDate startDate = LocalDate.now().minusMonths(6);
        LocalDate endDate = LocalDate.now();
        List<InventoryDropInfo> drops = new ArrayList<>();

        for (MaterialInventoryRet material : inventoryList) {
            List<MaterialInventoryDetailRet> details = inventoryService.queryInventoryDetail(material.getMaterialId());
            if (CollectionUtils.isEmpty(details)) {
                continue;
            }

            List<MaterialInventoryDetailRet> inRange = details.stream()
                    .filter(item -> {
                        LocalDate date = parseDate(item.getDocumentCreateDate());
                        return date != null && !date.isBefore(startDate) && !date.isAfter(endDate);
                    })
                    .sorted(Comparator.comparing(item -> parseDate(item.getDocumentCreateDate())))
                    .collect(Collectors.toList());

            if (inRange.size() < 2) {
                continue;
            }

            long first = inRange.get(0).getInventoryQuantity();
            long last = inRange.get(inRange.size() - 1).getInventoryQuantity();
            long delta = last - first;
            if (delta < 0) {
                drops.add(new InventoryDropInfo(material.getMaterialName(), delta));
            }
        }

        drops.sort(Comparator.comparingLong(item -> item.delta));
        return drops;
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() >= 10) {
            normalized = normalized.substring(0, 10);
        }
        try {
            return LocalDate.parse(normalized);
        } catch (Exception ignore) {
            return null;
        }
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private static class InventoryDropInfo {
        private final String materialName;
        private final long delta;

        private InventoryDropInfo(String materialName, long delta) {
            this.materialName = materialName;
            this.delta = delta;
        }
    }
}
