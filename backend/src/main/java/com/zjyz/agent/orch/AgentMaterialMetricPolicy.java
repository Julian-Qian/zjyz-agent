package com.zjyz.agent.orch;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Monetary intent guard, not a tool router. Quantity is never evidence of earnings. */
public final class AgentMaterialMetricPolicy {
    private static final Pattern EARNINGS = Pattern.compile(
            "(?:赚|挣)(?:钱|得|到|了|取|多少|最多|最少|的|不|没|得多|得少)|最赚钱|收益|创收");
    private static final Pattern EXCLUDED_METRIC = Pattern.compile(
            "(?:不看|不查|不统计|不比较|不考虑|不用算|无需统计|不要看|不要算|忽略)(?:材料的?|租出)?"
                    + "(?:利润|盈利|净利|毛利|收入|租金收入|回款|金额|成本|赚钱|收益)");

    private AgentMaterialMetricPolicy() {
    }

    public static Set<String> monetaryMetrics(String message) {
        String source = activeText(message);
        Set<String> metrics = new LinkedHashSet<>();
        if (containsAny(source, "利润", "盈利", "净利", "毛利", "扣除成本", "扣掉成本", "减去成本", "亏损", "赔钱")) {
            metrics.add("PROFIT");
        }
        if (containsAny(source, "收入", "营收", "租金贡献", "租金最高", "租金最多", "租金排名", "租金排行")) {
            metrics.add("RENTAL_INCOME");
        }
        if (containsAny(source, "回款", "实收", "收款", "到账", "收了多少钱")) {
            metrics.add("CASH_RECEIVED");
        }
        if (metrics.isEmpty() && EARNINGS.matcher(source).find()) {
            metrics.add("MONEY_UNSPECIFIED");
        }
        return metrics;
    }

    public static boolean incompatibleWithQuantity(String message) {
        return !monetaryMetrics(message).isEmpty() || containsAny(activeText(message),
                "金额", "多少钱", "费用", "成本", "应收", "应付", "租金", "单价", "价格", "收益率", "回报率");
    }

    public static boolean containsEarningsExpression(String message) {
        return EARNINGS.matcher(activeText(message)).find();
    }

    public static boolean mentionsMaterial(String message) {
        return containsAny(message == null ? "" : message, "材料", "物料", "钢管", "扣件", "脚手架");
    }

    public static boolean requestsMaterialMoney(String message) {
        // Project/customer finance and material occupancy may coexist in one query.
        String source = (message == null ? "" : message).replaceAll(
                "(?:项目|客户|供应商|公司|企业)(?:的)?(?:回款|收入|利润|应收|应付|收款)", "");
        for (String clause : source.split("[，,。；;]")) {
            if (mentionsMaterial(clause) && !monetaryMetrics(clause).isEmpty()) return true;
        }
        return false;
    }

    private static String activeText(String message) {
        return EXCLUDED_METRIC.matcher(message == null ? "" : message).replaceAll("");
    }

    private static boolean containsAny(String source, String... values) {
        for (String value : values) {
            if (source.contains(value)) return true;
        }
        return false;
    }
}
