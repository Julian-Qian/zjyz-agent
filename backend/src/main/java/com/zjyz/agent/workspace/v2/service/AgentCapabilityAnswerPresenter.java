package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import java.util.*;

/** User-facing introduction derived only from capabilities available in this workspace. */
final class AgentCapabilityAnswerPresenter {
    private AgentCapabilityAnswerPresenter() {}

    static String present(Map<String, Object> manifest) {
        Set<String> codes = new HashSet<>();
        Object capabilities = manifest.get("capabilities");
        if (capabilities instanceof List) for (Object value : (List<?>) capabilities) {
            if (value instanceof Map) {
                Map<?, ?> item = (Map<?, ?>) value;
                if (Boolean.TRUE.equals(item.get("available")) && item.get("code") instanceof String)
                    codes.add((String) item.get("code"));
            } else if (value instanceof AgentV2Models.Capability) {
                AgentV2Models.Capability item = (AgentV2Models.Capability) value;
                if (item.isAvailable()) codes.add(item.getCode());
            }
        }
        List<String> sections = new ArrayList<>();
        if (codes.contains("help.search")) sections.add("使用指导：介绍软件、讲解业务流程，并根据帮助资料说明操作步骤。你可以问：‘我是新用户，应该从哪里开始？’‘怎么新建租出项目？’‘如何查看材料归还记录？’");
        List<String> examples = new ArrayList<>();
        if (codes.contains("project.list")) examples.add("小何目前负责哪些项目");
        if (codes.contains("project.reconciliation_due")) examples.add("本月哪些项目需要对账");
        if (codes.contains("project.material_occupancy")) examples.add("某个项目还有哪些材料没归还");
        if (!examples.isEmpty()) sections.add("业务查询：在你有权限查看的项目范围内查询实际数据。例如：‘" + String.join("’‘", examples) + "’。回答会说明查询范围和统计口径。");
        Object features = manifest.get("conversationFeatures");
        Map<?, ?> featureMap = features instanceof Map ? (Map<?, ?>) features : Collections.emptyMap();
        List<String> analyses = new ArrayList<>();
        if (Boolean.TRUE.equals(featureMap.get("evidenceAnalysis"))) {
            if (codes.contains("material.transaction_aggregate")) analyses.add("根据材料流水比较不同材料的使用情况");
            if (codes.contains("project.activity")) analyses.add("比较项目的业务活跃情况");
        }
        if (Boolean.TRUE.equals(featureMap.get("documentReview"))) analyses.add("分析你上传的合同，指出原文中的疑点和缺失信息");
        if (!analyses.isEmpty()) sections.add("分析：" + String.join("；", analyses) + "。结论会附依据，资料不足时会说明。");
        if (sections.isEmpty()) return "当前没有可用的查询或指导功能，请稍后重试，或联系管理员确认访问权限。";
        return "你可以直接用日常语言提问，例如：\n\n" + String.join("\n\n", sections)
                + "\n\n询问‘怎么操作’时，我会给你操作说明；需要保存或提交业务数据时，由你在对应页面完成。";
    }
}
