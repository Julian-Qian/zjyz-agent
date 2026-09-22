package com.zjyz.agent.orch;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/** Shared deterministic matcher for the owner's cross-project action center. */
public final class AgentOwnerActionIntentPolicy {
    private static final Pattern EXPLICIT_CALENDAR_DATE = Pattern.compile(
            "(?:20\\d{2}(?:年|(?:[-/](?:0?[1-9]|1[0-2]))(?:[-/](?:0?[1-9]|[12]\\d|3[01]))?)?|(?<!\\d)(?:0?[1-9]|1[0-2])月份?)");
    private static final Pattern RISK_OR_ISSUE_CLAUSE = Pattern.compile(
            "(?:^|[，,、和与及或：:；;。！？?\\s])([^，,、和与及或：:；;。！？?\\s]{1,64})(?:风险|问题)");
    private static final Pattern RELATIVE_PERIOD = Pattern.compile(
            "(?:近|前|后)(?:[0-9一二三四五六七八九十百两半]+个?)(?:天|日|周|星期|月|个月|季度|季|年)");
    private static final Pattern OWNER_ACTION_COUNT = Pattern.compile(
            "\\d{1,3}\\s*(?:件(?:事|[^，,。！？?]{1,24}事项)|项(?:待办|行动|事情)?)");
    private AgentOwnerActionIntentPolicy() {
    }

    public static boolean isOwnerActionCenter(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String source = stripEvaluationPrefix(message);
        if (containsAny(source, "行动中心", "老板待办", "老板优先", "今日待办", "经营风险待办", "项目风险待办", "风险行动清单")) {
            return true;
        }
        int riskDimensions = countRiskDimensions(source);
        boolean hasProjectOrKnownRisk = containsAny(source, "项目") || riskDimensions >= 1;
        boolean ownerPriority = hasProjectOrKnownRisk
                && containsAny(source, "老板", "负责人", "管理层", "我")
                && containsAny(source, "先处理", "优先处理", "最该处理", "需要处理", "应该处理", "重点关注");
        boolean projectRiskRanking = hasProjectOrKnownRisk && containsAny(source, "项目", "项目风险")
                && containsAny(source, "排行", "排名", "最高", "最低", "优先级", "待办", "行动", "最需要关注", "最值得关注");
        boolean compositeRisk = riskDimensions >= 2;
        boolean genericHighRiskProjects = containsAny(source, "高风险") && containsAny(source, "项目");
        boolean explicitOwnerTopActions = isExplicitOwnerTopActions(source);
        boolean scopedProjectAction = isScopedProjectAction(source);
        return ownerPriority || projectRiskRanking || compositeRisk || genericHighRiskProjects
                || explicitOwnerTopActions || scopedProjectAction;
    }

    /** A deictic project reference is only executable when the frozen workspace contains exactly one project. */
    public static boolean isScopedProjectAction(String message) {
        if (!StringUtils.hasText(message)) return false;
        String source = stripEvaluationPrefix(message);
        return containsAny(source, "这个项目", "当前项目", "本项目")
                && containsAny(source, "风险")
                && containsAny(source, "处理", "待办", "关注", "行动");
    }

    /** Owner action center is a today-only snapshot; non-today requests must fail closed. */
    public static boolean hasUnsupportedSnapshotTime(String message) {
        if (!StringUtils.hasText(message)) return false;
        String source = stripEvaluationPrefix(message);
        if (EXPLICIT_CALENDAR_DATE.matcher(source).find()) return true;
        if (RELATIVE_PERIOD.matcher(source).find()) return true;
        if (containsAny(source, "预测", "未来", "最近", "近期", "明天", "后天", "昨天", "昨日", "前天",
                "明年", "来年", "前年", "下周", "下个月", "下月", "前一周", "前一个月",
                "上周", "上个月", "上月", "去年", "过去", "历史", "本周", "本月", "今年", "本年",
                "上半年", "下半年", "本季度", "上季度", "下季度")) {
            return true;
        }
        return containsAny(source, "截至", "截止")
                && !containsAny(source, "截至今天", "截至今日", "截止今天", "截止今日", "截至现在", "截止现在",
                "截至目前", "截止目前");
    }

    /**
     * Detects risk/problem clauses that cannot be covered by the deterministic dimensions.
     * Generic project/operating/high-risk wording remains valid; a compound query is rejected
     * if even one concrete clause falls outside the supported phrase whitelist.
     */
    public static boolean hasUnknownRiskDimension(String message) {
        if (!StringUtils.hasText(message)) return false;
        String source = stripEvaluationPrefix(message);
        boolean explicitOwnerTopActions = isExplicitOwnerTopActions(source);
        boolean actionCompoundContext = countRiskDimensions(source) > 0
                && source.matches(".*[、和与及].*")
                && containsAny(source, "老板", "负责人", "管理层", "处理", "待办", "行动", "优先", "关注", "满足");
        boolean riskContext = containsAny(source, "风险")
                || (containsAny(source, "问题") && (containsAny(source, "老板", "负责人", "管理层", "处理", "待办", "行动")
                || countRiskDimensions(source) > 0))
                || actionCompoundContext
                || explicitOwnerTopActions;
        if (!riskContext) return false;
        if (explicitOwnerTopActions && !isCoveredRiskClause(source)) return true;
        java.util.regex.Matcher matcher = RISK_OR_ISSUE_CLAUSE.matcher(source);
        while (matcher.find()) {
            String clause = matcher.group(1);
            if (!isCoveredRiskClause(clause)) return true;
        }
        if (countRiskDimensions(source) > 0 && source.matches(".*[、和与及].*")) {
            for (String clause : source.split("[、和与及]")) {
                if (!isCoveredRiskClause(clause)) return true;
            }
        }
        return false;
    }

    private static boolean isCoveredRiskClause(String clause) {
        if (!StringUtils.hasText(clause)) return true;
        String residual = clause.trim();
        for (String supported : new String[]{
                "合同到期", "临近到期", "未录合同", "缺少合同", "合同",
                "材料未归还", "未归还材料", "未归还", "材料占用", "仍在租",
                "未对账", "待对账", "对账缺口",
                "逾期应收款", "应收款逾期", "逾期应收", "客户欠款", "催缴",
                "长期无活动", "没有活动", "无业务活动", "长期没动",
                "库存异常", "库存为负",
                "待审核单据", "未审核单据", "待复核单据", "待审核", "未审核", "待复核"}) {
            residual = residual.replace(supported, "");
        }
        for (String allowed : new String[]{
                "老板行动中心", "行动中心", "老板待办", "经营风险待办", "项目风险待办", "风险行动清单",
                "老板", "负责人", "管理层", "我", "请", "帮我", "给我", "这个", "本", "当前", "现在", "今天",
                "哪些", "什么", "列出", "查看", "检查", "总结", "汇总", "统计", "存在", "有", "的",
                "最需要关注", "最值得关注", "需要关注", "重点关注", "最该处理", "优先处理",
                "应该处理", "需要处理", "亲自处理", "先处理", "最需要", "应该", "需要", "亲自", "优先", "先", "处理", "关注",
                "项目经营", "项目", "经营", "综合", "整体", "总体", "高", "重点",
                "风险", "问题", "排行", "排名", "待办", "行动", "同时", "全部", "任一", "满足",
                "件事", "事项", "事情", "是"}) {
            residual = residual.replace(allowed, "");
        }
        residual = residual.replaceAll("(?i)(?:前|top\\s*)\\d{1,3}个?", "");
        residual = residual.replaceAll("\\d{1,3}(?:件|项)", "");
        residual = residual.replaceAll("\\d{1,3}", "");
        return residual.replaceAll("[\\s，,。：:；;！？?（）()]+", "").isEmpty();
    }

    private static boolean isExplicitOwnerTopActions(String source) {
        return containsAny(source, "老板")
                && containsAny(source, "亲自处理", "最需要处理", "最需要老板", "优先处理", "最该处理")
                && OWNER_ACTION_COUNT.matcher(source).find();
    }

    private static int countRiskDimensions(String source) {
        int count = 0;
        if (containsAny(source, "合同到期", "合同风险", "临近到期", "未录合同", "缺少合同")) count++;
        if (containsAny(source, "材料未归还", "未归还材料", "未归还", "材料占用", "仍在租")) count++;
        if (containsAny(source, "未对账", "待对账", "对账缺口")) count++;
        if (containsAny(source, "逾期应收款", "应收款逾期", "逾期应收", "客户欠款", "催缴")) count++;
        if (containsAny(source, "长期无活动", "没有活动", "无业务活动", "长期没动")) count++;
        if (containsAny(source, "库存异常", "库存为负")) count++;
        if (containsAny(source, "待审核单据", "未审核单据", "待复核单据", "待审核", "未审核", "待复核")) count++;
        return count;
    }

    private static boolean containsAny(String text, String... keywords) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String stripEvaluationPrefix(String message) {
        return message.replaceFirst("(?i)^\\s*\\[?E2E[^\\]：:]{0,40}\\]?\\s*[：:]\\s*", "");
    }
}
