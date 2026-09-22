package com.zjyz.agent.workspace.context;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAnswerGuardTest {

    @Test
    void blocksBusinessFactsWithoutToolEvidence() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setRequiresBusinessData(true);
        frame.setMinimumRequiredTools(Collections.singletonList("project.list"));

        AgentAnswerGuard.Decision decision = new AgentAnswerGuard()
                .evaluate(frame, false, "共有10个项目");

        assertFalse(decision.isPassed());
        assertTrue(decision.getAnswer().contains("没有获得可核验的业务工具结果"));
    }

    @Test
    void keepsAnswerWhenEvidenceExists() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setRequiresBusinessData(true);
        frame.setMinimumRequiredTools(Collections.singletonList("project.list"));

        AgentAnswerGuard.Decision decision = new AgentAnswerGuard()
                .evaluate(frame, Collections.singletonList("project_list"), "共有10个项目");

        assertTrue(decision.isPassed());
        assertEquals("共有10个项目", decision.getAnswer());
    }

    @Test
    void blocksAnswerWhenEvidenceIsFromWrongBusinessTool() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setRequiresBusinessData(true);
        frame.setRequiredToolCodes(Arrays.asList(
                "project.reconciliation_due",
                "finance.receivable_collection.list"));

        AgentAnswerGuard.Decision decision = new AgentAnswerGuard().evaluate(
                frame,
                Collections.singletonList("project.reconciliation_due"),
                "没有逾期项目");

        assertFalse(decision.isPassed());
        assertEquals(Collections.singletonList("finance.receivable_collection_list"), decision.getMissingToolCodes());
        assertTrue(decision.getAnswer().contains("部分只读业务数据"));
        assertTrue(decision.getAnswer().contains("【结论边界】"));
    }

    @Test
    void minimumRequiredKnowledgeToolIsEnforcedEvenWithoutBusinessDataFlag() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setRequiresBusinessData(false);
        frame.setMinimumRequiredTools(Collections.singletonList("help.search"));

        AgentAnswerGuard.Decision decision = new AgentAnswerGuard().evaluate(
                frame,
                Collections.emptyList(),
                "公司制度要求先审核后提交");

        assertFalse(decision.isPassed());
        assertEquals(Collections.singletonList("help.search"), decision.getMissingToolCodes());
        assertTrue(decision.getAnswer().contains("【缺失证据】help.search"));
        assertFalse(decision.getAnswer().contains("先审核后提交"));
    }

    @Test
    void unsupportedMetricCannotBeUnlockedByWeakRelatedTool() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setCoverage(AgentTaskCoverage.UNSUPPORTED_BUSINESS);
        frame.setRequiresBusinessData(false);
        frame.setMinimumRequiredTools(Collections.emptyList());

        AgentAnswerGuard.Decision decision = new AgentAnswerGuard().evaluate(
                frame,
                Collections.singletonList("project.list"),
                "A项目利润最高");

        assertFalse(decision.isPassed());
        assertTrue(decision.isUnsupported());
        assertTrue(decision.getAnswer().contains("【缺失能力】"));
        assertFalse(decision.getAnswer().contains("A项目利润最高"));
    }

    @Test
    void canonicalizesRequiredAndProvidedAliasesButKeepsUnknownAliasDistinct() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setRequiresBusinessData(true);
        frame.setMinimumRequiredTools(Collections.singletonList("finance.receivable_collection.list"));

        AgentAnswerGuard.Decision aliasMatch = new AgentAnswerGuard().evaluate(
                frame,
                Collections.singletonList("finance_receivable_collection_list"),
                "已取得应收结果");
        AgentAnswerGuard.Decision unknownAlias = new AgentAnswerGuard().evaluate(
                frame,
                Collections.singletonList("finance_receivable_unknown"),
                "已取得应收结果");

        assertTrue(aliasMatch.isPassed());
        assertFalse(unknownAlias.isPassed());
        assertEquals(Collections.singletonList("finance.receivable_collection_list"),
                unknownAlias.getMissingToolCodes());
    }

    @Test
    void enterpriseKnowledgeBoundaryNamesTheMissingCapabilityWithoutProjectScopeAdvice() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setDomain("ENTERPRISE_KNOWLEDGE");
        frame.setCoverage(AgentTaskCoverage.UNSUPPORTED_BUSINESS);
        frame.setRequiresBusinessData(true);
        frame.setMinimumRequiredTools(Collections.emptyList());

        AgentAnswerGuard.Decision decision = new AgentAnswerGuard().evaluate(
                frame,
                Collections.singletonList("project.contract_status"),
                "合同都在有效期内");

        assertFalse(decision.isPassed());
        assertTrue(decision.getAnswer().contains("尚未接入企业自有知识库"));
        assertTrue(decision.getAnswer().contains("不会用合同状态、项目数据或系统帮助内容替代"));
        assertFalse(decision.getAnswer().contains("调整项目范围"));
        assertFalse(decision.getAnswer().contains("合同都在有效期内"));
    }

    @Test
    void financeCoreCanonicalAliasesMustMatchExactRequiredEvidence() {
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setCoverage(AgentTaskCoverage.SUPPORTED_TOOL);
        frame.setRequiresBusinessData(true);
        frame.setMinimumRequiredTools(Collections.singletonList("finance.supplier.payable.summary"));

        AgentAnswerGuard.Decision exact = new AgentAnswerGuard().evaluate(frame,
                Collections.singletonList("finance_supplier_payable_summary"), "应付结果");
        AgentAnswerGuard.Decision weak = new AgentAnswerGuard().evaluate(frame,
                Collections.singletonList("finance.enterprise_kpi"), "应付结果");

        assertTrue(exact.isPassed());
        assertFalse(weak.isPassed());
        assertEquals(Collections.singletonList("finance.supplier_payable_summary"), weak.getMissingToolCodes());
    }

    @Test
    void compositeUnsupportedMetricCannotBeUnlockedByAnySuccessfulTools() {
        AgentTaskFrame frame = new AgentTaskFrameBuilder().build(
                "供应商应付以及实际出账金额是多少？", "", null, java.time.ZoneId.of("Asia/Shanghai"));

        AgentAnswerGuard.Decision decision = new AgentAnswerGuard().evaluate(frame,
                Arrays.asList("finance.supplier_payable_summary", "finance.enterprise_kpi", "project.list"),
                "供应商已付100元，银行实际出账也是100元");

        assertFalse(decision.isPassed());
        assertTrue(decision.isUnsupported());
        assertFalse(decision.getAnswer().contains("100元"));
        assertTrue(decision.getRequiredToolCodes().isEmpty());
    }

    @Test
    void singleProjectCompositeLedgerRequiresEnterpriseKpiEvidenceOnly() {
        com.zjyz.agent.workspace.model.AgentRuntimeRecords.Workspace workspace =
                new com.zjyz.agent.workspace.model.AgentRuntimeRecords.Workspace();
        workspace.setSelectionMode("EXPLICIT");
        workspace.setProjectId("project-1");
        workspace.setProjectIds(Collections.singletonList("project-1"));
        AgentTaskFrame frame = new AgentTaskFrameBuilder().build(
                "这个项目现在应收、已收和未清多少？", "", workspace,
                java.time.ZoneId.of("Asia/Shanghai"));

        AgentAnswerGuard.Decision canonicalAlias = new AgentAnswerGuard().evaluate(
                frame, Collections.singletonList("finance_enterprise_kpi"), "当前项目财务台账结果");
        AgentAnswerGuard.Decision projectSummary = new AgentAnswerGuard().evaluate(
                frame, Collections.singletonList("project.get_summary"), "项目概况结果");
        AgentAnswerGuard.Decision collection = new AgentAnswerGuard().evaluate(
                frame, Collections.singletonList("finance.receivable_collection_list"), "催缴结果");

        assertTrue(canonicalAlias.isPassed());
        assertFalse(projectSummary.isPassed());
        assertFalse(collection.isPassed());
        assertEquals(Collections.singletonList("finance.enterprise_kpi"),
                projectSummary.getMissingToolCodes());
        assertEquals(Collections.singletonList("finance.enterprise_kpi"),
                collection.getMissingToolCodes());
    }

    @Test
    void ownerActionCenterAcceptsCanonicalAliasAndRejectsAdjacentRiskTools() {
        AgentTaskFrame frame = new AgentTaskFrameBuilder().build(
                "老板行动中心", "", null, java.time.ZoneId.of("Asia/Shanghai"));

        AgentAnswerGuard.Decision alias = new AgentAnswerGuard().evaluate(
                frame, Collections.singletonList("risk_owner_action_center"), "可用维度行动结果");
        AgentAnswerGuard.Decision adjacent = new AgentAnswerGuard().evaluate(
                frame, Arrays.asList("project.contract_status", "project.activity"), "自行拼出的风险结果");

        assertTrue(alias.isPassed());
        assertFalse(adjacent.isPassed());
        assertEquals(Collections.singletonList("risk.owner_action_center"), adjacent.getMissingToolCodes());
    }
}
