package com.zjyz.agent.orch;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared deterministic finance intent matching for task framing and degraded execution. */
public final class AgentFinanceIntentPolicy {
    private static final Pattern EXPLICIT_ISO_DATE = Pattern.compile("20\\d{2}-\\d{2}-\\d{2}");
    private static final Pattern YEAR_MONTH = Pattern.compile("20\\d{2}(?:年|[-/])(?:0?[1-9]|1[0-2])(?:月)?(?![-/\\d])");
    private static final Pattern MONTH_ONLY = Pattern.compile("(?<!\\d)(?:0?[1-9]|1[0-2])月份?(?!\\d)");
    private AgentFinanceIntentPolicy() {
    }

    public static boolean isEnterpriseKpi(String message) {
        if (AgentUnsupportedMetricPolicy.containsUnsupportedMetric(message)
                || isUnsupportedHistoricalBalance(message)) {
            return false;
        }
        boolean enterpriseScope = containsAny(message, "公司", "企业", "全部项目", "所有项目", "整体", "总体");
        boolean pairedLedger = (containsAny(message, "应收") && containsAny(message, "应付"))
                || (containsAny(message, "已收", "实收") && containsAny(message, "已付", "实付"))
                || containsAny(message, "应收应付", "已收已付", "财务台账", "财务总览");
        boolean customerRegisteredCash = enterpriseScope
                && containsAny(message, "已收", "实收", "回款", "登记收款")
                && (containsAny(message, "多少", "金额", "合计", "总额", "统计", "汇总")
                || containsAny(message, "登记收款"));
        boolean supplierRegisteredCash = enterpriseScope
                && containsAny(message, "已付", "实付", "登记付款")
                && (containsAny(message, "多少", "金额", "合计", "总额", "统计", "汇总")
                || containsAny(message, "登记付款"));
        boolean rentReceipt = containsAny(message, "租金")
                && containsAny(message, "收了多少", "实收", "收到", "回款");
        boolean scopedProjectLedger = isScopedProjectLedgerKpi(message);
        return (enterpriseScope && pairedLedger) || customerRegisteredCash || supplierRegisteredCash
                || rentReceipt || scopedProjectLedger;
    }

    public static boolean isScopedProjectLedgerKpi(String message) {
        return containsAny(message, "这个项目", "本项目", "当前项目", "该项目")
                && containsAny(message, "应收")
                && containsAny(message, "已收", "实收", "回款", "未清")
                && containsAny(message, "多少", "金额", "合计", "总额", "统计", "汇总", "分别")
                && !containsAny(message, "逾期", "催缴", "追缴", "催款", "回款清单", "到期");
    }

    public static boolean isSupplierPayable(String message) {
        if (AgentUnsupportedMetricPolicy.containsUnsupportedMetric(message)
                || isUnsupportedHistoricalBalance(message)
                || containsAny(message, "成本预测", "运输成本")
                || containsAny(message, "快到付款日", "即将付款", "未来付款", "付款日快到")) {
            return false;
        }
        return isSupplierDirection(message)
                && containsAny(message, "应付", "未付", "已付", "实付", "付款", "支付", "到期");
    }

    public static boolean isSupplierDirection(String message) {
        return containsAny(message, "租入", "供应商");
    }

    public static boolean hasNonCurrentCutoffReference(String message) {
        if (!StringUtils.hasText(message) || !containsAny(message, "截至", "截止")) {
            return false;
        }
        return !containsAny(message, "截至今天", "截至今日", "截至现在", "截至目前", "截至当前",
                "截止今天", "截止今日", "截止现在", "截止目前", "截止当前");
    }

    public static boolean isUnsupportedHistoricalBalance(String message) {
        // “到期/逾期”本身也用于合同期限等非财务事实，不能单独把月份合同查询拦成历史财务余额。
        boolean balanceMetric = containsAny(message, "应收", "应付", "未清", "未付", "欠款", "余额",
                "财务总览", "财务台账");
        boolean explicitlyCurrent = containsAny(message, "当前", "现在", "目前", "截至今天", "截至今日", "今日余额");
        return hasHistoricalTemporalReference(message) && balanceMetric && !explicitlyCurrent;
    }

    public static boolean hasHistoricalTemporalReference(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return hasNonCurrentCutoffReference(message)
                || containsAny(message, "昨天", "上周末", "上月", "上个月", "上月末", "去年", "去年末", "历史",
                "本月", "这个月", "今年", "本年")
                || EXPLICIT_ISO_DATE.matcher(message).find()
                || YEAR_MONTH.matcher(message).find()
                || MONTH_ONLY.matcher(message).find();
    }

    private static boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String source = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (source.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
