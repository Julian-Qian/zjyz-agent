package com.zjyz.agent.orch;

import org.springframework.util.StringUtils;

import java.util.Locale;

/** Metrics for which the current deterministic data model cannot produce a trustworthy answer. */
public final class AgentUnsupportedMetricPolicy {
    private static final String[] KEYWORDS = {
            "资产", "资产总额", "资产价值", "资产估值",
            "税", "税额", "税费", "税金", "含税", "不含税", "发票",
            "利润", "盈利", "净利", "毛利", "净利润",
            "营业额", "营收", "营业收入", "销售收入",
            "完整现金流", "现金流", "净流入", "净流出", "资金流",
            "银行", "到账金额", "实际到账", "实际出账",
            "押金余额", "未退押金", "押金没退", "押金未退", "退押金"
    };

    private AgentUnsupportedMetricPolicy() {
    }

    public static boolean containsUnsupportedMetric(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        if (AgentMaterialMetricPolicy.containsEarningsExpression(message)) {
            return true;
        }
        String source = message.toLowerCase(Locale.ROOT);
        for (String keyword : KEYWORDS) {
            if (source.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
