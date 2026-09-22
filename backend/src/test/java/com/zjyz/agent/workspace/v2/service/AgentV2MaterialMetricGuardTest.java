package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class AgentV2MaterialMetricGuardTest {
    @ParameterizedTest
    @ValueSource(strings = {"今年帮我赚到最多钱的材料是什么", "哪个材料最赚钱", "哪个材料给我挣得最多",
            "哪些材料租得最多但不赚钱", "今年材料收益最高的是哪个"})
    void originalEarningsCannotBeRewrittenIntoQuantity(String current) {
        AgentV2Models.TaskSpec spec = spec("今年租出材料数量排行", "QUANTITY");
        AgentV2MaterialMetricGuard.Decision decision = AgentV2MaterialMetricGuard.evaluate(context(current), spec);
        assertNotNull(decision);
        assertTrue(decision.needsClarification());
        assertTrue(decision.answer().contains("租金收入"));
        assertTrue(decision.answer().contains("利润"));
    }

    @Test
    void interpreterMayNotGuessProfitFromAnAmbiguousQuestion() {
        assertTrue(AgentV2MaterialMetricGuard.evaluate(context("哪种材料赚钱最多"),
                spec("材料利润排行", "PROFIT")).needsClarification());
    }

    @ParameterizedTest
    @ValueSource(strings = {"材料租金贡献最大的是哪个", "材料收入排行", "材料实际回款最多的是哪个",
            "材料扣除成本后的利润最高的是谁", "材料租出数量和利润都列出来"})
    void explicitFinancialMetricsReportCapabilityGapInsteadOfPartialQuantityAnswer(String current) {
        AgentV2MaterialMetricGuard.Decision decision = AgentV2MaterialMetricGuard.evaluate(context(current),
                spec("材料数量排行", "QUANTITY"));
        assertNotNull(decision);
        assertFalse(decision.needsClarification());
        assertTrue(decision.answer().contains("尚不支持"));
    }

    @Test
    void clarificationAnswerUsesResolvedMaterialContextWithoutRepeatingQuestion() {
        AgentV2MaterialMetricGuard.Decision decision = AgentV2MaterialMetricGuard.evaluate(context("租金收入"),
                spec("今年材料租金收入最高的是哪个", "RENTAL_INCOME"));
        assertNotNull(decision);
        assertFalse(decision.needsClarification());
    }

    @Test
    void semanticMetricCatchesParaphrasesWithoutKnownMoneyKeywords() {
        assertNotNull(AgentV2MaterialMetricGuard.evaluate(context("哪种货的贡献最大"),
                spec("比较各种货的经济贡献", "RENTAL_INCOME")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"今年租出最多的材料是什么", "材料发生次数排行", "不看利润，只看材料租出数量",
            "不要看租金收入，只看材料租出数量", "材料归还数量排行"})
    void quantityAndExplicitCorrectionRemainAvailable(String current) {
        assertNull(AgentV2MaterialMetricGuard.evaluate(context(current), spec("材料租出数量排行", "QUANTITY")));
    }

    @Test
    void staleHistoryDoesNotContaminateNewQuantityQuestion() {
        AgentV2Models.BoundedContext context = context("材料租出数量排行");
        AgentV2Models.ContextMessage old = new AgentV2Models.ContextMessage();
        old.setRole("user");
        old.setContent("哪个材料最赚钱");
        context.setMessages(Arrays.asList(old, context.getMessages().get(0)));
        assertNull(AgentV2MaterialMetricGuard.evaluate(context, spec("材料租出数量排行", "QUANTITY")));
    }

    @Test
    void projectCashQueryIsNotBlockedByMaterialGuard() {
        AgentV2Models.TaskSpec spec = spec("项目回款排行", "CASH_RECEIVED");
        spec.setAnalysisTarget("PROJECT");
        assertNull(AgentV2MaterialMetricGuard.evaluate(context("项目回款排行"), spec));
    }

    @Test
    void materialOccupancyAndProjectCashRemainSeparateSupportedMetrics() {
        AgentV2Models.TaskSpec spec = spec("查询未归还材料和项目回款", "CASH_RECEIVED");
        spec.setAnalysisTarget("PROJECT");
        assertNull(AgentV2MaterialMetricGuard.evaluate(context("查询未归还材料和项目回款"), spec));
    }

    @Test
    void contractUnitPriceAnalysisIsNotMistakenForEarnings() {
        AgentV2Models.TaskSpec spec = spec("比较材料合同日租金单价", "OTHER");
        assertNull(AgentV2MaterialMetricGuard.evaluate(context("比较材料合同日租金单价"), spec));
    }

    private AgentV2Models.TaskSpec spec(String goal, String metric) {
        AgentV2Models.TaskSpec spec = new AgentV2Models.TaskSpec();
        spec.setResolvedGoal(goal);
        spec.setAnalysisTarget("MATERIAL");
        spec.setRequestedMetrics(Collections.singletonList(metric));
        return spec;
    }

    private AgentV2Models.BoundedContext context(String current) {
        AgentV2Models.BoundedContext context = new AgentV2Models.BoundedContext();
        AgentV2Models.ContextMessage message = new AgentV2Models.ContextMessage();
        message.setRole("user");
        message.setContent(current);
        context.setMessages(Collections.singletonList(message));
        return context;
    }
}
