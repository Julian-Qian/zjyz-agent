package com.zjyz.agent.orch.skill;

import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.*;
import com.zjyz.pojo.param.ret.MaterialProcessRet;
import com.zjyz.service.ProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class ProjectRentMaterialsSkillHandler implements AgentSkillHandler {

    @Autowired
    private ProjectService projectService;

    @Override
    public AgentIntentType supportedIntent() {
        return AgentIntentType.PROJECT_RENT_MATERIALS;
    }

    @Override
    public boolean requiresProjectId(AgentSlotBag slots) {
        return true;
    }

    @Override
    public AgentSkillExecution execute(String message, Map<String, Object> context, AgentSlotBag slots) {
        String projectId = slots == null ? null : slots.getProjectId();
        if (!StringUtils.hasText(projectId)) {
            return AgentSkillExecution.clarification(
                    "project_rent_materials",
                    "请补充项目ID或项目名称，我可以立即查询该项目在租中的材料。",
                    Collections.singletonList("projectId"),
                    buildEvidence(0, "rent_out")
            );
        }

        String businessType = slots == null ? "rent_out" : normalizeBusinessType(slots.getProjectBusinessType());
        List<MaterialProcessRet> processList = "rent_in".equals(businessType)
                ? projectService.queryRentInProcess(projectId)
                : projectService.queryRentOutProcess(projectId);

        List<MaterialProcessRet> inRentList = CollectionUtils.isEmpty(processList)
                ? Collections.emptyList()
                : processList.stream()
                .filter(item -> item != null && safeInt(item.getQuantity()) > 0)
                .sorted(Comparator.comparingInt((MaterialProcessRet item) -> safeInt(item.getQuantity())).reversed())
                .collect(Collectors.toList());

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("project_rent_materials");
        execution.setConfidence(0.92);
        execution.setEvidence(buildEvidence(inRentList.size(), businessType));

        if (CollectionUtils.isEmpty(inRentList)) {
            execution.setAnswer("项目 " + projectId + " 当前未查询到在租材料。");
            execution.setCards(Collections.singletonList(new LinkedHashMap<String, Object>() {{
                put("type", "project-rent-materials");
                put("projectId", projectId);
                put("businessType", businessType);
                put("inRentCount", 0);
                put("items", Collections.emptyList());
            }}));
            return execution;
        }

        String preview = inRentList.stream()
                .limit(5)
                .map(item -> item.getMaterialName() + "(" + safeInt(item.getQuantity()) + ")")
                .collect(Collectors.joining("、"));

        execution.setAnswer("项目 " + projectId + " 当前在租材料 " + inRentList.size() + " 种，示例：" + preview + "。");
        execution.setCards(Collections.singletonList(new LinkedHashMap<String, Object>() {{
            put("type", "project-rent-materials");
            put("projectId", projectId);
            put("businessType", businessType);
            put("inRentCount", inRentList.size());
            put("items", inRentList.stream().limit(20).collect(Collectors.toList()));
        }}));
        return execution;
    }

    private AgentEvidence buildEvidence(int recordCount, String businessType) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + LocalDate.now());
        evidence.setSkills(Collections.singletonList("ProjectRentMaterialSkill"));
        evidence.setApiList(Collections.singletonList("rent_in".equals(businessType)
                ? "/project/queryRentInProcess"
                : "/project/queryRentOutProcess"));
        evidence.setRecordCount(recordCount);
        return evidence;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String normalizeBusinessType(String value) {
        if (!StringUtils.hasText(value)) {
            return "rent_out";
        }
        return "rent_in".equalsIgnoreCase(value.trim()) ? "rent_in" : "rent_out";
    }
}
