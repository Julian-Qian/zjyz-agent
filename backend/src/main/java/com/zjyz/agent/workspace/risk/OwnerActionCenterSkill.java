package com.zjyz.agent.workspace.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.ProjectAction;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.Query;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.Result;
import com.zjyz.common.exception.MyBizException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class OwnerActionCenterSkill {
    public static final String TOOL_CODE = "risk.owner_action_center";
    private static final int DEFAULT_LIMIT = 20;
    private static final Pattern LIMIT_PATTERN = Pattern.compile(
            "(?:(?:前|top\\s*)(\\d{1,3})|(\\d{1,3})(?=\\s*(?:件事|项(?:待办|行动|事情)?)))",
            Pattern.CASE_INSENSITIVE);

    private final OwnerActionCenterService service;
    private final ObjectMapper objectMapper;

    public OwnerActionCenterSkill(OwnerActionCenterService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public AgentSkillExecution execute(String argumentsJson,
                                       String originalMessage,
                                       AgentRuntimeRecords.Workspace workspace) {
        if (com.zjyz.agent.orch.AgentOwnerActionIntentPolicy.isScopedProjectAction(originalMessage)
                && !isSingleProject(workspace)) {
            throw new MyBizException("“这个项目”仅能在明确选中一个项目时使用", "AGT400");
        }
        Query query = parseQuery(argumentsJson, originalMessage);
        Result result = service.generate(workspace, query);
        Map<String, Object> card = objectMapper.convertValue(result, Map.class);
        card.put("type", "owner-action-center");
        card.put("selectionMode", workspace.getSelectionMode());
        card.put("scopeNote", "项目行动分仅按可用且可靠归属的维度计算；材料数量按unit分组，库存异常因无法可靠归属projectId暂不进入项目分。" );

        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + result.getAsOfDate());
        evidence.setSkills(Collections.singletonList("OwnerActionCenterSkill"));
        evidence.setApiList(Arrays.asList(
                "internal:project", "internal:contract", "internal:settlement_document",
                "internal:rent_document", "internal:return_document", "internal:compensation_document",
                "internal:rent_in_document", "internal:rent_in_return_document",
                "internal:rent_document_material", "internal:return_document_material",
                "internal:rent_in_document_material", "internal:rent_in_return_document_material",
                "internal:reconciliation_document", "internal:finance_ledger_snapshot"));
        evidence.setRecordCount(result.getActionTotalCount());

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("risk_owner_action_center");
        execution.setConfidence(1.0d);
        execution.setAnswer(buildAnswer(result));
        execution.setCards(Collections.singletonList(card));
        execution.setWarnings(result.getWarnings());
        execution.setEvidence(evidence);
        return execution;
    }

    private boolean isSingleProject(AgentRuntimeRecords.Workspace workspace) {
        return workspace != null
                && "EXPLICIT".equalsIgnoreCase(workspace.getSelectionMode())
                && workspace.getProjectIds() != null
                && workspace.getProjectIds().size() == 1
                && StringUtils.hasText(workspace.getProjectIds().get(0));
    }

    private Query parseQuery(String argumentsJson, String message) {
        if (com.zjyz.agent.orch.AgentOwnerActionIntentPolicy.hasUnknownRiskDimension(message)) {
            throw new MyBizException("问题包含行动中心尚无可靠事实源的风险维度，不能只回答相邻的已支持维度", "AGT400");
        }
        if (com.zjyz.agent.orch.AgentOwnerActionIntentPolicy.hasUnsupportedSnapshotTime(message)) {
            throw new MyBizException("老板行动中心仅支持截至今天的实时快照，不支持历史截止或未来预测", "AGT400");
        }
        Query query = new Query();
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                if (root.path("riskTypes").isArray()) {
                    List<String> risks = new ArrayList<>();
                    root.path("riskTypes").forEach(value -> risks.add(value.asText()));
                    query.setRiskTypes(risks);
                }
                if (root.hasNonNull("matchMode")) query.setMatchMode(root.path("matchMode").asText());
                if (root.hasNonNull("projectStatus")) query.setProjectStatus(root.path("projectStatus").asText());
                if (root.hasNonNull("limit")) query.setLimit(root.path("limit").asInt());
                if (root.has("asOfDate") || root.has("scoringVersion") || root.has("scoreFormula")) {
                    throw new MyBizException("行动中心不允许模型设置截止日期或评分公式", "AGT400");
                }
            } catch (MyBizException error) {
                throw error;
            } catch (Exception error) {
                throw new MyBizException("老板行动中心参数格式不正确", "AGT400");
            }
        }
        enrichFromMessage(query, message);
        return query;
    }

    private void enrichFromMessage(Query query, String message) {
        String source = safe(message);
        List<String> risks = new ArrayList<>();
        if (containsAny(source, "合同到期", "合同风险", "临近到期", "未录合同", "缺少合同")) risks.add(OwnerActionCenterServiceImpl.CONTRACT_EXPIRED);
        if (containsAny(source, "材料未归还", "未归还材料", "未归还", "材料占用", "仍在租")) risks.add(OwnerActionCenterServiceImpl.MATERIAL_OUTSTANDING);
        if (containsAny(source, "未对账", "待对账", "对账缺口")) risks.add(OwnerActionCenterServiceImpl.UNRECONCILED);
        if (containsAny(source, "逾期应收款", "应收款逾期", "逾期应收", "客户欠款", "催缴")) risks.add(OwnerActionCenterServiceImpl.OVERDUE_RECEIVABLE);
        if (containsAny(source, "长期无活动", "没有活动", "无业务活动", "长期没动")) risks.add(OwnerActionCenterServiceImpl.INACTIVE_PROJECT);
        if (containsAny(source, "库存异常", "库存为负")) risks.add(OwnerActionCenterServiceImpl.INVENTORY_ANOMALY);
        if (containsAny(source, "待审核单据", "未审核单据", "待复核单据", "待审核", "未审核", "待复核")) risks.add(OwnerActionCenterServiceImpl.PENDING_REVIEW);
        query.setRiskTypes(risks.stream().distinct().collect(Collectors.toList()));
        if (query.getRiskTypes() != null && !query.getRiskTypes().isEmpty()
                && containsAny(source, "同时", "都存在", "兼有", "共同存在", "同时满足", "全部满足")) {
            query.setMatchMode("ALL");
        } else query.setMatchMode("ANY");
        if (containsAny(source, "已完成项目", "已结束项目")) query.setProjectStatus("COMPLETED");
        else if (containsAny(source, "全部状态", "所有状态", "进行中和已完成")) query.setProjectStatus("ALL");
        else query.setProjectStatus("ONGOING");
        Matcher limit = LIMIT_PATTERN.matcher(source);
        if (limit.find()) {
            String value = limit.group(1) == null ? limit.group(2) : limit.group(1);
            query.setLimit(Integer.parseInt(value));
        } else query.setLimit(DEFAULT_LIMIT);
    }

    private String buildAnswer(Result result) {
        boolean hasFailed = result.getFailedDimensions() != null && !result.getFailedDimensions().isEmpty();
        boolean hasUnsupported = result.getUnsupportedDimensions() != null && !result.getUnsupportedDimensions().isEmpty();
        String boundary;
        if (hasFailed) {
            boundary = "注意：有风险维度核算失败，本次仅按可用维度排序（rankingBasis=AVAILABLE_DIMENSIONS），不能视为完整或最高风险排名。";
        } else if (hasUnsupported) {
            boundary = "注意：有风险维度当前不支持或未启用，本次仅按可用维度排序（rankingBasis=AVAILABLE_DIMENSIONS），不能视为完整或最高风险排名。";
        } else if (Boolean.TRUE.equals(result.getScoreIncomplete())) {
            boundary = "注意：部分项目不适用所选风险维度，本次仅按具有可靠覆盖的项目和维度排序（rankingBasis=AVAILABLE_DIMENSIONS），不能视为完整或最高风险排名。";
        } else {
            boundary = "评分采用 " + result.getScoringVersion() + "，各分数组成可从结果卡复算。";
        }
        if (result.getProjectTotalCount() == 0) {
            String conclusion = Boolean.TRUE.equals(result.getScoreIncomplete())
                    ? "当前可评估维度中没有匹配项目；未评估维度不能据此判断为无风险。"
                    : "当前范围没有符合 " + result.getQuery().getMatchMode() + " 匹配条件的项目。";
            return boundary + " 截至 " + result.getAsOfDate() + "，" + conclusion;
        }
        String preview = result.getItems().stream().limit(5)
                .map(item -> safe(item.getProjectName()) + "（" + item.getTotalScore() + "分，"
                        + item.getRiskCount() + "项风险）")
                .collect(Collectors.joining("、"));
        return boundary + " 截至 " + result.getAsOfDate() + "，当前范围匹配 "
                + result.getProjectTotalCount() + " 个项目、" + result.getActionTotalCount()
                + " 项行动；展示前 " + result.getDisplayedProjectCount() + " 个项目：" + preview + "。";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) if (safe(text).contains(keyword)) return true;
        return false;
    }

    private String safe(String value) { return value == null ? "" : value; }
}
