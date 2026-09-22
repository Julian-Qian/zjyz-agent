package com.zjyz.agent.workspace.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Query;
import com.zjyz.agent.orch.AgentFinanceIntentPolicy;
import com.zjyz.common.exception.MyBizException;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FinanceLedgerQueryParser {
    private static final Pattern DATE_RANGE = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2}).{0,12}(20\\d{2}-\\d{2}-\\d{2})");
    private static final Pattern YEAR_MONTH = Pattern.compile("(20\\d{2})(?:年|[-/])(0?[1-9]|1[0-2])(?:月)?(?![-/\\d])");
    private static final Pattern MONTH_ONLY = Pattern.compile("(?<!\\d)(0?[1-9]|1[0-2])月份?(?!\\d)");

    private FinanceLedgerQueryParser() {
    }

    static Query parse(ObjectMapper objectMapper, String argumentsJson, String message) {
        Query query = new Query();
        if (StringUtils.hasText(argumentsJson)) {
            try {
                JsonNode root = objectMapper.readTree(argumentsJson);
                if (root.hasNonNull("asOfDate")) query.setAsOfDate(root.get("asOfDate").asText());
                if (root.hasNonNull("startDate")) query.setStartDate(root.get("startDate").asText());
                if (root.hasNonNull("endDate")) query.setEndDate(root.get("endDate").asText());
                if (root.hasNonNull("limit")) query.setLimit(root.get("limit").asInt());
            } catch (Exception e) {
                throw new MyBizException("财务查询参数格式不正确", "FIN400");
            }
        }
        enrichDates(query, message);
        query.setRequestedMetric(resolveRequestedMetric(message));
        if (AgentFinanceIntentPolicy.isUnsupportedHistoricalBalance(message)) {
            throw new MyBizException("当前台账没有历史状态版本；历史期间只支持按payment_date统计登记收付，不能查询历史余额", "FIN400");
        }
        if (AgentFinanceIntentPolicy.hasHistoricalTemporalReference(message)
                && isRegisteredCashMetric(query.getRequestedMetric())
                && (!StringUtils.hasText(query.getStartDate()) || !StringUtils.hasText(query.getEndDate()))) {
            throw new MyBizException("历史登记收付必须提供可识别的完整期间，不能静默改用累计金额", "FIN400");
        }
        return query;
    }

    private static void enrichDates(Query query, String message) {
        if (StringUtils.hasText(query.getStartDate()) || StringUtils.hasText(query.getEndDate())) return;
        String source = message == null ? "" : message;
        LocalDate today = LocalDate.now();
        if (source.contains("上个月") || source.contains("上月")) {
            YearMonth month = YearMonth.from(today).minusMonths(1);
            query.setStartDate(month.atDay(1).toString());
            query.setEndDate(month.atEndOfMonth().toString());
            return;
        }
        if (source.contains("本月") || source.contains("这个月")) {
            query.setStartDate(today.withDayOfMonth(1).toString());
            query.setEndDate(today.toString());
            return;
        }
        if (source.contains("今年") || source.contains("本年")) {
            query.setStartDate(LocalDate.of(today.getYear(), 1, 1).toString());
            query.setEndDate(today.toString());
            return;
        }
        if (source.contains("去年")) {
            int year = today.getYear() - 1;
            query.setStartDate(LocalDate.of(year, 1, 1).toString());
            query.setEndDate(LocalDate.of(year, 12, 31).toString());
            return;
        }
        Matcher range = DATE_RANGE.matcher(source);
        if (range.find()) {
            query.setStartDate(range.group(1));
            query.setEndDate(range.group(2));
            return;
        }
        Matcher yearMonth = YEAR_MONTH.matcher(source);
        if (yearMonth.find()) {
            YearMonth month = YearMonth.of(Integer.parseInt(yearMonth.group(1)),
                    Integer.parseInt(yearMonth.group(2)));
            query.setStartDate(month.atDay(1).toString());
            query.setEndDate(month.equals(YearMonth.from(today))
                    ? today.toString() : month.atEndOfMonth().toString());
            return;
        }
        Matcher monthOnly = MONTH_ONLY.matcher(source);
        if (monthOnly.find()) {
            YearMonth current = YearMonth.from(today);
            YearMonth month = YearMonth.of(current.getYear(), Integer.parseInt(monthOnly.group(1)));
            if (month.isAfter(current)) month = month.minusYears(1);
            query.setStartDate(month.atDay(1).toString());
            query.setEndDate(month.atEndOfMonth().toString());
        }
    }

    private static String resolveRequestedMetric(String message) {
        String source = message == null ? "" : message;
        if (source.contains("到期")) return "DUE_PAYABLE";
        if (source.contains("未付")) return "OUTSTANDING_PAYABLE";
        if (source.contains("应收")
                && containsAny(source, "已收", "实收", "回款", "登记收款")
                && containsAny(source, "未清", "欠款")) {
            return "FINANCE_LEDGER_OVERVIEW";
        }
        if ((source.contains("租金") && (source.contains("收了多少") || source.contains("实收")
                || source.contains("回款"))) && !source.contains("供应商")) {
            return "CUSTOMER_RENT_RECEIPTS";
        }
        if (containsAny(source, "已收", "实收", "回款", "登记收款")
                && !containsAny(source, "已付", "实付", "登记付款")) {
            return "CUSTOMER_REGISTERED_RECEIPTS";
        }
        boolean supplierPaymentCash = containsAny(source, "已付", "实付", "登记付款", "支付", "付了多少")
                || (source.contains("付款") && containsAny(source, "多少", "金额", "合计", "总额", "统计", "汇总"));
        if (supplierPaymentCash
                && !containsAny(source, "已收", "实收", "回款", "登记收款")) {
            return "SUPPLIER_REGISTERED_PAYMENTS";
        }
        return "FINANCE_LEDGER_OVERVIEW";
    }

    private static boolean containsAny(String source, String... values) {
        for (String value : values) {
            if (source.contains(value)) return true;
        }
        return false;
    }

    private static boolean isRegisteredCashMetric(String metric) {
        return "CUSTOMER_RENT_RECEIPTS".equals(metric)
                || "CUSTOMER_REGISTERED_RECEIPTS".equals(metric)
                || "SUPPLIER_REGISTERED_PAYMENTS".equals(metric);
    }
}
