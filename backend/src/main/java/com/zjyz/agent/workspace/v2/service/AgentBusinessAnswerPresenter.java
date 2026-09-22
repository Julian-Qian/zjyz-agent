package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.orch.AgentSkillExecution;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic presentation only. Original tool results, cards and evidence remain unchanged. */
final class AgentBusinessAnswerPresenter {
    private AgentBusinessAnswerPresenter() {
    }

    static String compose(List<AgentSkillExecution> executions, List<String> plannerWarnings) {
        Set<String> answers = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        if (executions != null) {
            for (AgentSkillExecution execution : executions) {
                if (execution == null) continue;
                if (StringUtils.hasText(execution.getAnswer())) answers.add(businessText(execution.getAnswer()));
                addWarnings(warnings, execution.getWarnings());
            }
        }
        addWarnings(warnings, plannerWarnings);
        String answer = answers.isEmpty() ? "查询已完成，详情见结果卡。" : String.join("\n\n", answers);
        if (!warnings.isEmpty()) answer += "\n\n说明：" + String.join("", warnings);
        return answer;
    }

    private static void addWarnings(Set<String> target, List<String> values) {
        if (values == null) return;
        for (String value : values) {
            if (!StringUtils.hasText(value)) continue;
            String warning = AgentAnswerPresentation.present(conciseWarning(value.trim()));
            if (StringUtils.hasText(warning)) target.add(warning.replaceAll("[。；;]+$", "") + "。");
        }
    }

    private static String conciseWarning(String value) {
        // Only known static boundary explanations are consolidated. Unknown/data-quality warnings survive.
        if (value.startsWith("本卡仅表示系统内正式结算本金和登记付款")
                || value.startsWith("本卡仅表示系统内正式应付本金和登记付款")) {
            return "以上是系统账款和登记收付款，不代表利润或完整财务报表";
        }
        if (value.startsWith("余额仅表示本地今天的当前台账快照")) {
            return "余额反映当前状态，不能据此还原历史余额";
        }
        if (value.startsWith("登记实收实付来自系统付款记录")
                || value.startsWith("登记实付来自系统付款记录")
                || value.startsWith("期间实收和实付按非删除付款记录的payment_date统计")) {
            return "收付款按系统登记的付款日期统计，不等同于银行到账或账款冲抵金额";
        }
        if (value.startsWith("到期口径采用正式结算账期结束日")) {
            return "到期判断依据结算账期结束日，未核验合同约定的付款期限";
        }
        if (value.startsWith("押金仅表示登记收取金额；系统没有押金退还台账")) {
            return "押金只统计登记收取金额，无法计算尚未退还的余额";
        }
        return businessText(value);
    }

    private static String businessText(String value) {
        return value.trim()
                .replace("dueDateBasis=SETTLEMENT_PERIOD_END", "")
                .replace("SETTLEMENT_PERIOD_END", "")
                .replace("ACTIVE本金核销", "已用于冲抵本金的金额")
                .replace("payment_date", "付款日期")
                .replace("v1 不含合同价与单据执行价偏差核对。", "本次未核对合同价与单据执行价的差异。")
                .replace("企业全量库存口径：", "企业全部库存中，")
                .replace("生命周期核查：", "核查结果：")
                .replace("登记实收实付与本金核销是不同口径。", "登记收付款与账款冲抵分别统计。")
                .replaceAll("[。；][；]+", "。")
                .trim();
    }
}
