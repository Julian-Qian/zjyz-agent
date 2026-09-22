package com.zjyz.agent.workspace.context;

import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.tool.AgentToolCodes;
import lombok.Getter;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentAnswerGuard {
    private static final String NO_EVIDENCE_MESSAGE =
            "【已确认】本轮没有获得可核验的业务工具结果。\n"
                    + "【缺失证据】完成该问题所需的只读业务数据未返回。\n"
                    + "【结论边界】因此不能可靠输出项目、单据、库存或财务数字、名单、排行及判断。\n"
                    + "【下一步】请调整项目范围后重试，或联系管理员补充相应只读工具。";

    public Decision evaluate(AgentTaskFrame taskFrame, boolean hasToolEvidence, String proposedAnswer) {
        return evaluate(taskFrame,
                hasToolEvidence ? Collections.singletonList("legacy.any") : Collections.emptyList(),
                proposedAnswer);
    }

    public Decision evaluate(AgentTaskFrame taskFrame,
                             List<String> successfulToolCodes,
                             String proposedAnswer) {
        return evaluate(taskFrame, successfulToolCodes, null, proposedAnswer);
    }

    /**
     * 覆盖校验升级（P0-3）：除"必需工具是否执行"外，还校验必需工具证据的
     * 时间范围、项目范围与记录数要素；要素缺失不拦截答案，但按 PARTIAL 处理
     * 并在答案中显式声明。对象与单位要素由各业务卡的口径字段承载。
     */
    public Decision evaluate(AgentTaskFrame taskFrame,
                             List<String> successfulToolCodes,
                             List<AgentSkillExecution> executions,
                             String proposedAnswer) {
        List<String> required = normalizeCodes(taskFrame == null
                ? Collections.emptyList() : taskFrame.getMinimumRequiredTools());
        AgentTaskCoverage coverage = taskFrame == null ? AgentTaskCoverage.NON_BUSINESS : taskFrame.getCoverage();
        boolean requiresEvidence = taskFrame != null
                && (coverage != AgentTaskCoverage.NON_BUSINESS
                || taskFrame.isRequiresBusinessData() || !required.isEmpty());
        boolean hasToolEvidence = !CollectionUtils.isEmpty(successfulToolCodes);
        boolean unsupported = taskFrame != null
                && (coverage == AgentTaskCoverage.UNSUPPORTED_BUSINESS
                || (coverage != AgentTaskCoverage.SUPPORTED_TOOL
                && taskFrame.isRequiresBusinessData() && required.isEmpty()));
        Set<String> provided = new LinkedHashSet<>(normalizeCodes(successfulToolCodes));
        List<String> missing = new ArrayList<>();
        for (String toolCode : required) {
            if (!provided.contains(toolCode)) {
                missing.add(toolCode);
            }
        }
        boolean passed = !unsupported
                && (!requiresEvidence || (hasToolEvidence && missing.isEmpty()));
        String guardedAnswer = proposedAnswer;
        if (!passed) {
            boolean enterpriseKnowledge = taskFrame != null
                    && "ENTERPRISE_KNOWLEDGE".equals(taskFrame.getDomain());
            guardedAnswer = unsupported && enterpriseKnowledge
                    ? "【已确认】你查询的是公司上传的制度、合同模板或操作规范。\n"
                    + "【缺失能力】当前小云尚未接入企业自有知识库的确定性检索工具。\n"
                    + "【结论边界】因此不会用合同状态、项目数据或系统帮助内容替代回答。\n"
                    + "【下一步】请在企业知识检索能力接入后重试，或直接提供文档内容让我协助整理。"
                    : unsupported
                    ? "【已确认】当前问题涉及需要业务数据计算的指标。\n"
                    + "【缺失能力】系统尚未提供该指标对应的确定性只读工具。\n"
                    + "【结论边界】即使本轮读取了其他弱相关数据，也不能据此输出数字、排行、预测或经营判断。\n"
                    + "【下一步】请改问系统当前支持的项目、单据、材料流水、占用、合同或对账指标。"
                    : !hasToolEvidence
                    ? (missing.isEmpty() ? NO_EVIDENCE_MESSAGE
                    : "【已确认】本轮没有获得可核验的业务工具结果。\n"
                    + "【缺失证据】" + String.join("、", missing) + "。\n"
                    + "【结论边界】因此不能可靠输出确定性数字、名单、排行或判断。\n"
                    + "【下一步】请调整项目范围后重试，或联系管理员补充上述只读工具。")
                    : "【已确认】本轮已获得部分只读业务数据。\n"
                    + "【缺失证据】" + String.join("、", missing) + "。\n"
                    + "【结论边界】关键证据没有完整覆盖问题，因此未输出可能误导的确定性数字、名单、排行或判断。\n"
                    + "【下一步】请调整项目范围后重试，或联系管理员补充上述只读工具。";
        }
        List<String> coverageGaps = passed ? coverageGaps(required, executions) : Collections.emptyList();
        if (passed && !coverageGaps.isEmpty()) {
            guardedAnswer = guardedAnswer + "\n\n口径提示：" + String.join("；", coverageGaps)
                    + "。相关数字请以结果卡的口径说明为准。";
        }
        String capability = unsupported ? "UNSUPPORTED"
                : !passed ? "PARTIAL"
                : coverageGaps.isEmpty() ? "SUPPORTED" : "PARTIAL";
        return new Decision(requiresEvidence, hasToolEvidence, unsupported, passed, guardedAnswer,
                required, missing, capability, coverageGaps);
    }

    private List<String> coverageGaps(List<String> required, List<AgentSkillExecution> executions) {
        if (executions == null || executions.isEmpty() || required.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, AgentEvidence> byCode = new LinkedHashMap<>();
        for (AgentSkillExecution execution : executions) {
            if (execution == null || execution.getEvidence() == null) {
                continue;
            }
            String code = AgentToolCodes.canonicalize(execution.getEvidence().getToolCode());
            if (StringUtils.hasText(code)) {
                byCode.putIfAbsent(code, execution.getEvidence());
            }
        }
        List<String> gaps = new ArrayList<>();
        for (String code : required) {
            AgentEvidence evidence = byCode.get(code);
            if (evidence == null) {
                continue;
            }
            List<String> missingElements = new ArrayList<>();
            if (!StringUtils.hasText(evidence.getTimeRange())) {
                missingElements.add("时间范围");
            }
            if (!StringUtils.hasText(evidence.getSelectionMode())
                    && CollectionUtils.isEmpty(evidence.getProjectIds())) {
                missingElements.add("项目范围");
            }
            if (evidence.getRecordCount() == null) {
                missingElements.add("记录数");
            }
            if (!missingElements.isEmpty()) {
                gaps.add("关键证据缺少" + String.join("、", missingElements) + "声明（" + code + "）");
            }
        }
        return gaps;
    }

    private List<String> normalizeCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String code : codes) {
            String canonical = AgentToolCodes.canonicalize(code);
            if (canonical != null && !canonical.isEmpty()) {
                result.add(canonical);
            }
        }
        return new ArrayList<>(result);
    }

    @Getter
    public static class Decision {
        private final boolean requiresToolEvidence;
        private final boolean toolEvidencePresent;
        private final boolean unsupported;
        private final boolean passed;
        private final String answer;
        private final List<String> requiredToolCodes;
        private final List<String> missingToolCodes;
        /** SUPPORTED / PARTIAL / UNSUPPORTED 三态能力判定。 */
        private final String capability;
        private final List<String> coverageGaps;

        private Decision(boolean requiresToolEvidence,
                         boolean toolEvidencePresent,
                         boolean unsupported,
                         boolean passed,
                         String answer,
                         List<String> requiredToolCodes,
                         List<String> missingToolCodes,
                         String capability,
                         List<String> coverageGaps) {
            this.requiresToolEvidence = requiresToolEvidence;
            this.toolEvidencePresent = toolEvidencePresent;
            this.unsupported = unsupported;
            this.passed = passed;
            this.answer = answer;
            this.requiredToolCodes = requiredToolCodes == null
                    ? Collections.emptyList() : new ArrayList<>(requiredToolCodes);
            this.missingToolCodes = missingToolCodes == null
                    ? Collections.emptyList() : new ArrayList<>(missingToolCodes);
            this.capability = capability;
            this.coverageGaps = coverageGaps == null
                    ? Collections.emptyList() : new ArrayList<>(coverageGaps);
        }
    }
}
