package com.zjyz.agent.workspace.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Query;
import com.zjyz.common.exception.MyBizException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FinanceLedgerQueryParserTest {

    @Test
    void compositeSingleProjectReceivableUsesLedgerOverviewInsteadOfCashOnlyMetric() {
        Query query = FinanceLedgerQueryParser.parse(
                new ObjectMapper(), "{}", "这个项目现在应收、已收和未清多少？");

        assertEquals("FINANCE_LEDGER_OVERVIEW", query.getRequestedMetric());
    }

    @Test
    void historicalRegisteredCashMetricsAreExplicitAndUsePaymentPeriod() {
        YearMonth previous = YearMonth.from(LocalDate.now()).minusMonths(1);

        Query receipts = FinanceLedgerQueryParser.parse(new ObjectMapper(), "{}", "上个月公司已收多少");
        Query payments = FinanceLedgerQueryParser.parse(new ObjectMapper(), "{}", "上个月公司已付多少");
        Query supplierPayments = FinanceLedgerQueryParser.parse(
                new ObjectMapper(), "{}", "上个月向供应商支付了多少钱");
        Query supplierPaymentAmount = FinanceLedgerQueryParser.parse(
                new ObjectMapper(), "{}", "上月供应商付款多少");

        assertEquals("CUSTOMER_REGISTERED_RECEIPTS", receipts.getRequestedMetric());
        assertEquals("SUPPLIER_REGISTERED_PAYMENTS", payments.getRequestedMetric());
        assertEquals("SUPPLIER_REGISTERED_PAYMENTS", supplierPayments.getRequestedMetric());
        assertEquals("SUPPLIER_REGISTERED_PAYMENTS", supplierPaymentAmount.getRequestedMetric());
        assertEquals(previous.atDay(1).toString(), receipts.getStartDate());
        assertEquals(previous.atEndOfMonth().toString(), receipts.getEndDate());
    }

    @Test
    void historicalBalanceLanguageFailsClosedUnlessBalanceIsExplicitlyCurrent() {
        for (String question : java.util.Arrays.asList(
                "上个月公司应收应付多少",
                "上月供应商应付多少",
                "截至2026-07-31公司应收应付多少",
                "2026-07-31公司应收应付多少",
                "2026年7月公司应收应付多少",
                "2026-07公司应付多少",
                "7月份应付多少",
                "截至昨天公司应收应付多少",
                "今年供应商到期未付多少",
                "截至2026-07-31逾期应收多少",
                "公司上月财务总览",
                "去年企业财务台账")) {
            assertEquals("FIN400", assertThrows(MyBizException.class, () -> FinanceLedgerQueryParser.parse(
                    new ObjectMapper(), "{}", question), question).getErrorCode(), question);
        }

        Query allowed = FinanceLedgerQueryParser.parse(
                new ObjectMapper(), "{}", "当前应收应付和本月已收已付");
        assertEquals("FINANCE_LEDGER_OVERVIEW", allowed.getRequestedMetric());
        assertEquals(LocalDate.now().withDayOfMonth(1).toString(), allowed.getStartDate());
        assertEquals(LocalDate.now().toString(), allowed.getEndDate());
    }

    @Test
    void chineseYearMonthAndMonthOnlyCashQuestionsProduceExplicitPeriods() {
        Query julyRent = FinanceLedgerQueryParser.parse(
                new ObjectMapper(), "{}", "2026年7月收了多少租金");
        Query julySupplier = FinanceLedgerQueryParser.parse(
                new ObjectMapper(), "{}", "7月份供应商实付多少");

        assertEquals("2026-07-01", julyRent.getStartDate());
        assertEquals("2026-07-31", julyRent.getEndDate());
        assertEquals("CUSTOMER_RENT_RECEIPTS", julyRent.getRequestedMetric());
        assertEquals("2026-07-01", julySupplier.getStartDate());
        assertEquals("2026-07-31", julySupplier.getEndDate());
        assertEquals("SUPPLIER_REGISTERED_PAYMENTS", julySupplier.getRequestedMetric());
    }
}
