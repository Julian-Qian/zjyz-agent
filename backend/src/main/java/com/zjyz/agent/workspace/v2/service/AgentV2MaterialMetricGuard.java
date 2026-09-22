package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.orch.AgentMaterialMetricPolicy;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Checks the requested metric before planning, including the unmodified current user message. */
final class AgentV2MaterialMetricGuard {
    static final String CLARIFICATION = "你说的“赚钱”是指租金收入，还是扣除成本后的利润？"
            + "目前小云尚不支持按材料可靠汇总收入、回款或利润；租出数量不能代表赚钱金额。";
    static final String UNAVAILABLE = "目前小云尚不支持按材料可靠汇总租金收入、实际回款或利润，"
            + "因此无法判断哪种材料赚钱最多。材料收入需要可核对的结算明细，回款需要材料分摊依据，"
            + "利润还需要完整成本；不能用租出数量或项目收支替代。";
    private static final Set<String> MONEY = new LinkedHashSet<>(Arrays.asList(
            "MONEY_UNSPECIFIED", "RENTAL_INCOME", "CASH_RECEIVED", "PROFIT"));

    private AgentV2MaterialMetricGuard() {
    }

    static Decision evaluate(AgentV2Models.BoundedContext context, AgentV2Models.TaskSpec spec) {
        if (spec == null) return null;
        String current = currentUserMessage(context);
        String resolved = spec.getResolvedGoal() == null ? "" : spec.getResolvedGoal();
        if (!"MATERIAL".equals(spec.getAnalysisTarget())
                && !AgentMaterialMetricPolicy.requestsMaterialMoney(current)
                && !AgentMaterialMetricPolicy.requestsMaterialMoney(resolved)) return null;

        Set<String> rawMetrics = AgentMaterialMetricPolicy.monetaryMetrics(current);
        Set<String> metrics = new LinkedHashSet<>(rawMetrics);
        metrics.addAll(AgentMaterialMetricPolicy.monetaryMetrics(resolved));
        if (spec.getRequestedMetrics() != null) metrics.addAll(spec.getRequestedMetrics());
        metrics.retainAll(MONEY);
        if (metrics.isEmpty()) return null;

        // An interpreter may not silently turn an ambiguous original question into profit or quantity.
        boolean ambiguous = rawMetrics.equals(Collections.singleton("MONEY_UNSPECIFIED"))
                || (rawMetrics.isEmpty() && metrics.equals(Collections.singleton("MONEY_UNSPECIFIED")));
        if (ambiguous) metrics = Collections.singleton("MONEY_UNSPECIFIED");
        return new Decision(ambiguous, metrics);
    }

    private static String currentUserMessage(AgentV2Models.BoundedContext context) {
        List<AgentV2Models.ContextMessage> messages = context == null ? null : context.getMessages();
        if (messages != null) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                AgentV2Models.ContextMessage message = messages.get(index);
                if (message != null && "user".equals(message.getRole())) {
                    return message.getContent() == null ? "" : message.getContent();
                }
            }
        }
        return "";
    }

    static final class Decision {
        private final boolean clarification;

        private final Set<String> metrics;

        Decision(boolean clarification, Set<String> metrics) {
            this.clarification = clarification;
            this.metrics = metrics;
        }

        void preserveRequestedMetric(AgentV2Models.BoundedContext context, AgentV2Models.TaskSpec spec) {
            spec.setRequestedMetrics(new java.util.ArrayList<>(metrics));
            spec.setAnalysisTarget("MATERIAL");
            String current = currentUserMessage(context);
            if (AgentMaterialMetricPolicy.requestsMaterialMoney(current)) {
                spec.setGoal(current);
                spec.setResolvedGoal(current);
            }
        }

        boolean needsClarification() { return clarification; }
        String answer() { return clarification ? CLARIFICATION : UNAVAILABLE; }
    }
}
