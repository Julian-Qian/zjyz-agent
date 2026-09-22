package com.zjyz.agent.workspace.finance;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class FinanceLedgerSnapshotModels {
    private FinanceLedgerSnapshotModels() {
    }

    @Data
    public static class Query {
        private String asOfDate;
        private String startDate;
        private String endDate;
        private Integer limit;
        private String requestedMetric;
    }

    @Data
    public static class Result {
        private Query query;
        private DirectionSummary rentOut;
        private DirectionSummary rentIn;
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class DirectionSummary {
        private String businessDirection;
        private BigDecimal postedPrincipal;
        private BigDecimal allocatedPrincipalAsOf;
        private BigDecimal outstandingPrincipalAsOf;
        private BigDecimal dueAsOfOutstanding;
        private BigDecimal overdueOutstanding;
        private BigDecimal dueTodayOutstanding;
        private CashSummary cumulativeCash;
        private CashSummary periodCash;
        private Integer projectCount;
        private Integer periodCount;
        private List<ProjectSummary> projects = new ArrayList<>();
    }

    @Data
    public static class CashSummary {
        private BigDecimal totalRegistered;
        private BigDecimal principalEligible;
        private BigDecimal rent;
        private BigDecimal compensation;
        private BigDecimal deposit;
        private BigDecimal lateFee;
        private BigDecimal other;
        private BigDecimal allocatedPrincipalAsOf;
        private BigDecimal unallocatedPrincipalAsOf;
    }

    @Data
    public static class ProjectSummary {
        private String projectId;
        private String projectName;
        private String counterpartyName;
        private String counterpartyNameSource;
        private BigDecimal postedPrincipal;
        private BigDecimal allocatedPrincipalAsOf;
        private BigDecimal outstandingPrincipalAsOf;
        private BigDecimal dueAsOfOutstanding;
        private BigDecimal overdueOutstanding;
        private BigDecimal dueTodayOutstanding;
        private BigDecimal registeredCash;
        private BigDecimal principalEligibleCash;
        private BigDecimal unallocatedPrincipalCash;
        private BigDecimal cashRent;
        private BigDecimal cashCompensation;
        private BigDecimal cashDeposit;
        private BigDecimal cashLateFee;
        private BigDecimal cashOther;
        private BigDecimal periodRegisteredCash;
        private BigDecimal periodPrincipalEligibleCash;
        private BigDecimal periodCashRent;
        private BigDecimal periodCashCompensation;
        private BigDecimal periodCashDeposit;
        private BigDecimal periodCashLateFee;
        private BigDecimal periodCashOther;
        private Integer periodCount;
        private String earliestDueDate;
    }
}
