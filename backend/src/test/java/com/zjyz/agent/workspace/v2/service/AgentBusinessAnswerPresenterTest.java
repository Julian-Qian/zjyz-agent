package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.orch.AgentSkillExecution;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class AgentBusinessAnswerPresenterTest {
    @Test
    void compositeAnswerKeepsFactsAndScopeWithoutInternalFieldNamesOrDuplicateWarnings() {
        AgentSkillExecution inventory = result("企业全量库存口径：当前异常材料共 58 种（库存为负 24、在租为负 20、租入未退为负 0、长期无流水 14）。");
        AgentSkillExecution materials = result("当前选中范围内的租出项目生命周期核查：多还材料组 54 组；超过 60 天无归还流水的项目 3 个；发生过赔偿的材料组 0 组。");
        AgentSkillExecution contracts = result("当前选中的 9 个项目，6 个项目存在缺失，81 组材料定价不一致。v1 不含合同价与单据执行价偏差核对。");
        AgentSkillExecution finance = result("截至 2026-09-12，应收本金 507798.89 元，其中ACTIVE本金核销 0.00 元；按payment_date统计客户登记实收 0.00 元。");
        finance.setWarnings(Arrays.asList(
                "到期口径采用正式结算账期结束日 SETTLEMENT_PERIOD_END，不代表合同约定付款期限。",
                "到期口径采用正式结算账期结束日，不代表合同约定付款期限。 dueDateBasis=SETTLEMENT_PERIOD_END",
                "登记实收实付来自系统付款记录，不代表银行实际到账或出账，也不等同于ACTIVE本金核销。",
                "期间实收和实付按非删除付款记录的payment_date统计；本金已收和已付仅指截止今天的ACTIVE本金核销，两者不得互相替代。"));
        String original = finance.getAnswer();

        String answer = AgentBusinessAnswerPresenter.compose(Arrays.asList(inventory, materials, contracts, finance), Collections.emptyList());

        for (String fact : Arrays.asList("58", "24", "20", "14", "54", "60", "3", "9", "6", "81", "507798.89", "0.00", "2026-09-12")) {
            assertTrue(answer.contains(fact), fact);
        }
        assertTrue(answer.contains("企业全部库存"));
        assertTrue(answer.contains("当前选中范围内的租出项目"));
        assertTrue(answer.contains("未核对合同价与单据执行价"));
        for (String technical : Arrays.asList("ACTIVE", "payment_date", "SETTLEMENT_PERIOD_END", "dueDateBasis", "v1", "。；")) {
            assertFalse(answer.contains(technical), technical);
        }
        assertEquals(1, answer.split("到期判断依据", -1).length - 1);
        assertEquals(1, answer.split("收付款按系统登记", -1).length - 1);
        assertEquals(original, finance.getAnswer());
        assertTrue(finance.getWarnings().get(1).contains("dueDateBasis"));
    }

    @Test
    void preservesUnknownDataQualityAndPlannerWarnings() {
        AgentSkillExecution execution = result("应收本金 120.00 元。");
        execution.setWarnings(Arrays.asList("部分历史结算缺少明细，统计可能不完整。", "付款单 p-1 的ACTIVE本金核销金额超过实付金额"));
        String answer = AgentBusinessAnswerPresenter.compose(Collections.singletonList(execution),
                Collections.singletonList("部分工具明细因上下文总预算已截断"));
        assertTrue(answer.contains("部分历史结算缺少明细，统计可能不完整"));
        assertTrue(answer.contains("p-1"));
        assertTrue(answer.contains("超过实付金额"));
        assertTrue(answer.contains("已截断"));
    }

    @Test
    void preservesSimpleAnswerAndDoesNotAppendUnnecessaryExplanations() {
        assertEquals("第二名是钢管，共 120 根。", AgentBusinessAnswerPresenter.compose(
                Collections.singletonList(result("第二名是钢管，共 120 根。")), Collections.emptyList()));
    }

    @Test
    void guidanceRequirementsMergeOverlappingSourceParagraphsWithoutChangingEvidence() {
        AgentSkillExecution first=guidance("概览", "# 概览\n\n介绍软件。\n\n先建立项目。", "PUBLISHED_HELP");
        AgentSkillExecution second=guidance("概览", "先建立项目。\n\n再维护合同。", "PUBLISHED_HELP");
        AgentSkillExecution bundled=guidance("概览", "介绍软件。\n\n先建立项目。", "RELEASE_BUNDLED_OVERVIEW");
        String answer=AgentBusinessAnswerPresenter.compose(Arrays.asList(first,second,bundled), Collections.emptyList());
        assertEquals(1,answer.split("介绍软件。",-1).length-1);
        assertEquals(1,answer.split("先建立项目。",-1).length-1);
        assertEquals(1,answer.split("概览",-1).length-1);
        assertTrue(answer.contains("再维护合同。"));
        assertEquals("unchanged",first.getAnswer());
        assertTrue(first.getEvidence().getCriteria().toString().contains("# 概览"));
    }

    @Test
    void guidanceMergePreservesDifferentInstructionsAndBusinessAmounts() {
        AgentSkillExecution first=guidance("新建项目", "需要项目权限。\n\n点击新建。", "PUBLISHED_HELP");
        AgentSkillExecution second=guidance("材料归还", "核对数量后保存归还单。", "PUBLISHED_HELP");
        String answer=AgentBusinessAnswerPresenter.compose(Arrays.asList(first,second,result("租出 10 根，归还 10 根。")),Collections.emptyList());
        assertTrue(answer.contains("需要项目权限。"));assertTrue(answer.contains("点击新建。"));
        assertTrue(answer.contains("核对数量后保存归还单。"));assertTrue(answer.contains("租出 10 根，归还 10 根。"));
        assertEquals(1,answer.split("来源：",-1).length-1);
    }

    private AgentSkillExecution guidance(String title,String quote,String origin) {
        AgentSkillExecution execution=result("unchanged");execution.setIntent("help_knowledge");
        com.zjyz.agent.model.AgentEvidence evidence=new com.zjyz.agent.model.AgentEvidence();
        evidence.setCriteria(java.util.Map.of("sources",java.util.List.of(java.util.Map.of("title",title,"quote",quote,"source",origin))));
        execution.setEvidence(evidence);return execution;
    }

    private AgentSkillExecution result(String answer) {
        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setAnswer(answer);
        return execution;
    }
}
