package com.zjyz.agent.workspace.finance;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class ReceivableCollectionModels {
    private ReceivableCollectionModels() {
    }

    @Data
    public static class Query {
        private String asOfDate;
        private Integer year;
        private String scope;
        private BigDecimal minOutstandingAmount;
        private Integer minOverdueDays;
        private String projectKeyword;
        private String customerKeyword;
        private String managerName;
        private List<String> priorityLevels = new ArrayList<>();
        private String sortBy;
        private Integer limit;
        private Boolean includeClosedLate;
    }

    @Data
    public static class Result {
        private String schemaVersion = "1.0";
        private String generatedAt;
        private Query query;
        private Summary summary;
        private List<ProjectItem> items = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class Summary {
        private Integer projectCount;
        private Integer returnedProjectCount;
        private BigDecimal totalOutstanding;
        private BigDecimal currentYearOutstanding;
        private BigDecimal carryoverOutstanding;
        private Integer currentYearProjectCount;
        private Integer carryoverProjectCount;
        private Integer p1Count;
        private Integer p2Count;
        private Integer p3Count;
        private Integer maxOverdueDays;
    }

    @Data
    public static class ProjectItem {
        private String projectId;
        private String projectName;
        private String customerName;
        private String managerName;
        private String projectStatus;
        private String yearGroup;
        private BigDecimal outstandingAmount;
        private BigDecimal currentYearOutstanding;
        private BigDecimal carryoverOutstanding;
        private Integer overdueDays;
        private Integer periodCount;
        private String earliestDueDate;
        private String latestPaymentDate;
        private String priority;
        private String suggestedAction;
        private List<PeriodItem> periods = new ArrayList<>();
    }

    @Data
    public static class PeriodItem {
        private String periodId;
        private String settlementDocumentId;
        private String periodStart;
        private String periodEnd;
        private String dueDate;
        private Integer overdueDays;
        private BigDecimal principalAmount;
        private BigDecimal allocatedAsOf;
        private BigDecimal outstandingAsOf;
        private String yearGroup;
    }
}
