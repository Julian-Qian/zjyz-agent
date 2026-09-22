package com.zjyz.agent.workspace.context;

import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.tool.AgentToolCodes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P0-3 Guard 覆盖校验升级：必需工具证据的时间/范围/记录数要素缺失按 PARTIAL 处理并显式声明。 */
class AgentAnswerGuardCoverageTest {

    private final AgentAnswerGuard guard = new AgentAnswerGuard();

    @Test
    void completeEvidenceIsSupported() {
        AgentAnswerGuard.Decision decision = guard.evaluate(
                frame(AgentToolCodes.PROJECT_RECONCILIATION_DUE),
                Collections.singletonList(AgentToolCodes.PROJECT_RECONCILIATION_DUE),
                Collections.singletonList(execution(AgentToolCodes.PROJECT_RECONCILIATION_DUE,
                        "2026-08-01~2026-08-31", "ALL", 12)),
                "共有 3 个项目存在未对账区间。");
        assertTrue(decision.isPassed());
        assertEquals("SUPPORTED", decision.getCapability());
        assertTrue(decision.getCoverageGaps().isEmpty());
        assertEquals("共有 3 个项目存在未对账区间。", decision.getAnswer());
    }

    @Test
    void missingEvidenceElementsBecomePartialWithDeclaration() {
        AgentAnswerGuard.Decision decision = guard.evaluate(
                frame(AgentToolCodes.MATERIAL_TRANSACTION_AGGREGATE),
                Collections.singletonList(AgentToolCodes.MATERIAL_TRANSACTION_AGGREGATE),
                Collections.singletonList(execution(AgentToolCodes.MATERIAL_TRANSACTION_AGGREGATE,
                        null, "ALL", null)),
                "本月租出最多的材料是钢管。");
        assertTrue(decision.isPassed(), "要素缺失不拦截答案");
        assertEquals("PARTIAL", decision.getCapability());
        assertEquals(1, decision.getCoverageGaps().size());
        assertTrue(decision.getCoverageGaps().get(0).contains("时间范围"));
        assertTrue(decision.getCoverageGaps().get(0).contains("记录数"));
        assertTrue(decision.getAnswer().contains("口径提示"));
    }

    @Test
    void missingRequiredToolStaysBlockedAsPartial() {
        AgentAnswerGuard.Decision decision = guard.evaluate(
                frame(AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST),
                Collections.singletonList(AgentToolCodes.PROJECT_ACTIVITY),
                Collections.singletonList(execution(AgentToolCodes.PROJECT_ACTIVITY,
                        "asOf=2026-09-02", "ALL", 5)),
                "根据项目活动，应收情况良好。");
        assertFalse(decision.isPassed());
        assertEquals("PARTIAL", decision.getCapability());
        assertTrue(decision.getAnswer().contains("缺失证据"));
        assertFalse(decision.getAnswer().contains("应收情况良好"), "被拦截答案不得泄露原始结论");
    }

    @Test
    void unsupportedBusinessStaysUnsupported() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setCoverage(AgentTaskCoverage.UNSUPPORTED_BUSINESS);
        frame.setRequiresBusinessData(true);
        frame.setMinimumRequiredTools(new ArrayList<>());
        AgentAnswerGuard.Decision decision = guard.evaluate(frame,
                Collections.emptyList(), Collections.emptyList(), "利润大约是 100 万。");
        assertFalse(decision.isPassed());
        assertEquals("UNSUPPORTED", decision.getCapability());
    }

    @Test
    void legacyOverloadWithoutExecutionsSkipsCoverageCheck() {
        AgentAnswerGuard.Decision decision = guard.evaluate(
                frame(AgentToolCodes.PROJECT_RECONCILIATION_DUE),
                Collections.singletonList(AgentToolCodes.PROJECT_RECONCILIATION_DUE),
                "共有 3 个项目存在未对账区间。");
        assertTrue(decision.isPassed());
        assertEquals("SUPPORTED", decision.getCapability());
        assertTrue(decision.getCoverageGaps().isEmpty());
    }

    private AgentTaskFrame frame(String... required) {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setCoverage(AgentTaskCoverage.SUPPORTED_TOOL);
        frame.setRequiresBusinessData(true);
        frame.setMinimumRequiredTools(new ArrayList<>(Arrays.asList(required)));
        return frame;
    }

    private AgentSkillExecution execution(String toolCode,
                                          String timeRange,
                                          String selectionMode,
                                          Integer recordCount) {
        AgentSkillExecution execution = new AgentSkillExecution();
        AgentEvidence evidence = new AgentEvidence();
        evidence.setToolCode(toolCode);
        evidence.setTimeRange(timeRange);
        evidence.setSelectionMode(selectionMode);
        evidence.setRecordCount(recordCount);
        execution.setEvidence(evidence);
        return execution;
    }
}
