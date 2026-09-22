package com.zjyz.agent.workspace.risk;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OwnerActionCenterModels {
    public static final String SCORING_VERSION = "OWNER_ACTION_V1";

    private OwnerActionCenterModels() {
    }

    @Data
    public static class Query {
        private List<String> riskTypes = new ArrayList<>();
        private String matchMode = "ANY";
        private String projectStatus = "ONGOING";
        private Integer limit = 20;
    }

    @Data
    public static class ScoreContribution {
        private String code;
        private String label;
        private Integer points;
        private Object rawValue;
    }

    @Data
    public static class RiskFact {
        private String projectId;
        private String projectName;
        private String projectBusinessType;
        private String projectStatusFlag;
        private String managerName;
        private String riskType;
        private Map<String, Object> rawMetrics = new LinkedHashMap<>();
        private String evidenceDate;
        private String asOfDate;
        private Integer score;
        private List<ScoreContribution> scoreContributions = new ArrayList<>();
        private String suggestedAction;
    }

    @Data
    public static class ProjectAction {
        private String projectId;
        private String projectName;
        private String projectBusinessType;
        private String projectStatusFlag;
        private String managerName;
        private List<String> riskTypes = new ArrayList<>();
        private Integer riskCount;
        private Integer totalScore;
        private List<ScoreContribution> scoreContributions = new ArrayList<>();
        private String suggestedAction;
        private List<RiskFact> risks = new ArrayList<>();
    }

    @Data
    public static class UnsupportedDimension {
        private String riskType;
        private String reason;
        private String requiredCapability;
    }

    @Data
    public static class FailedDimension {
        private String riskType;
        private String errorCode;
        private String reason;
    }

    @Data
    public static class DimensionStatus {
        private String riskType;
        private String status;
        private Integer itemCount;
        private Integer applicableProjectCount;
        private Integer nonApplicableProjectCount;
        private List<String> nonApplicableProjectIds = new ArrayList<>();
        private String reason;
    }

    @Data
    public static class Result {
        private String schemaVersion = "1.0";
        private String scoringVersion = SCORING_VERSION;
        private String asOfDate;
        private String countEntity = "PROJECT";
        private Boolean scoreIncomplete = false;
        private String rankingBasis = "ALL_REQUESTED_DIMENSIONS";
        private Query query;
        private Integer totalProjectCount;
        private Integer projectRiskCount;
        private Integer projectTotalCount;
        private Integer displayedProjectCount;
        private Integer actionTotalCount;
        private Integer actionDisplayedCount;
        private Integer totalCount;
        private Integer displayedCount;
        private Boolean truncated;
        private Boolean actionTruncated;
        private Integer limit;
        private List<ProjectAction> items = new ArrayList<>();
        private List<RiskFact> topActions = new ArrayList<>();
        private List<UnsupportedDimension> unsupportedDimensions = new ArrayList<>();
        private List<FailedDimension> failedDimensions = new ArrayList<>();
        private List<DimensionStatus> dimensionStatuses = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }
}
