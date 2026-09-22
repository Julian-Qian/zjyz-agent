package com.zjyz.agent.orch.skill;

import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentIntentType;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.orch.AgentSkillHandler;
import com.zjyz.agent.orch.AgentSlotBag;
import com.zjyz.pojo.param.req.QueryProjectListParam;
import com.zjyz.pojo.param.ret.ContractInfoRet;
import com.zjyz.pojo.param.ret.ProjectListRet;
import com.zjyz.service.ContractService;
import com.zjyz.service.ProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ProjectSummarySkillHandler implements AgentSkillHandler {
    private static final int DEFAULT_PAGE_SIZE = 10_000;
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");

    @Autowired
    private ProjectService projectService;
    @Autowired
    private ContractService contractService;

    @Override
    public AgentIntentType supportedIntent() {
        return AgentIntentType.PROJECT_SUMMARY;
    }

    @Override
    public AgentSkillExecution execute(String message, Map<String, Object> context, AgentSlotBag slots) {
        boolean overdueRequested = slots != null && slots.isOverdueRequested();
        boolean anomalyRequested = containsAny(message, "异常", "波动", "环比", "趋势", "激增", "骤降");
        boolean dualBusinessRequested = isDualBusinessRequested(message);

        if (dualBusinessRequested) {
            BusinessSummary rentInSummary = buildBusinessSummary("rent_in", overdueRequested);
            BusinessSummary rentOutSummary = buildBusinessSummary("rent_out", overdueRequested);
            return buildDualBusinessExecution(rentInSummary, rentOutSummary, overdueRequested, anomalyRequested);
        }

        String businessType = slots == null ? "rent_out" : normalizeBusinessType(slots.getProjectBusinessType());
        BusinessSummary summary = buildBusinessSummary(businessType, overdueRequested);
        return buildSingleBusinessExecution(summary, overdueRequested, anomalyRequested);
    }

    private AgentSkillExecution buildSingleBusinessExecution(BusinessSummary summary,
                                                             boolean overdueRequested,
                                                             boolean anomalyRequested) {
        String businessLabel = businessLabel(summary.businessType);
        StringBuilder answer = new StringBuilder();
        answer.append("当前").append(businessLabel)
                .append("项目共 ").append(summary.total)
                .append(" 个，其中进行中 ").append(summary.ongoing)
                .append(" 个，已完成 ").append(summary.completed).append(" 个。");

        List<String> warnings = new ArrayList<>();
        if (anomalyRequested) {
            answer.append(" ").append(buildAnomalySentence(summary));
        }

        if (overdueRequested) {
            answer.append(" 疑似超期项目 ").append(summary.overdueProjects.size()).append(" 个。");
            if (!CollectionUtils.isEmpty(summary.overdueProjects)) {
                String example = summary.overdueProjects.stream()
                        .limit(5)
                        .map(item -> item.get("projectName") + "(截止" + item.get("contractEndDate") + ")")
                        .collect(Collectors.joining("、"));
                answer.append(" 示例：").append(example).append("。");
            } else {
                warnings.add("未发现疑似超期项目。");
            }
            if (summary.unknownEndDateCount > 0) {
                warnings.add("有 " + summary.unknownEndDateCount + " 个进行中项目缺少合同截止日期，无法参与超期判断。");
            }
        }

        Map<String, Object> card = buildBusinessCard(summary);
        card.put("type", "project-summary");
        card.put("anomalyRequested", anomalyRequested);

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("project_summary");
        execution.setConfidence(anomalyRequested ? 0.94 : 0.92);
        execution.setAnswer(answer.toString());
        execution.setCards(Collections.singletonList(card));
        execution.setWarnings(warnings);
        execution.setEvidence(buildEvidence(summary.total));
        return execution;
    }

    private AgentSkillExecution buildDualBusinessExecution(BusinessSummary rentInSummary,
                                                           BusinessSummary rentOutSummary,
                                                           boolean overdueRequested,
                                                           boolean anomalyRequested) {
        StringBuilder answer = new StringBuilder();
        answer.append("本月租入与租出概况：")
                .append("租入项目共 ").append(rentInSummary.total)
                .append(" 个（进行中 ").append(rentInSummary.ongoing)
                .append("，已完成 ").append(rentInSummary.completed).append("）；")
                .append("租出项目共 ").append(rentOutSummary.total)
                .append(" 个（进行中 ").append(rentOutSummary.ongoing)
                .append("，已完成 ").append(rentOutSummary.completed).append("）。");

        List<String> warnings = new ArrayList<>();
        if (anomalyRequested) {
            answer.append(" ").append(buildAnomalySentence(rentInSummary));
            answer.append(" ").append(buildAnomalySentence(rentOutSummary));
            answer.append(" 综合判断：").append(buildCombinedAnomalyConclusion(rentInSummary, rentOutSummary)).append("。");
        }

        if (overdueRequested) {
            answer.append(" 疑似超期：租入 ").append(rentInSummary.overdueProjects.size())
                    .append(" 个，租出 ").append(rentOutSummary.overdueProjects.size()).append(" 个。");

            if (!CollectionUtils.isEmpty(rentOutSummary.overdueProjects)) {
                String example = rentOutSummary.overdueProjects.stream()
                        .limit(3)
                        .map(item -> item.get("projectName") + "(截止" + item.get("contractEndDate") + ")")
                        .collect(Collectors.joining("、"));
                answer.append(" 租出示例：").append(example).append("。");
            }
            if (!CollectionUtils.isEmpty(rentInSummary.overdueProjects)) {
                String example = rentInSummary.overdueProjects.stream()
                        .limit(3)
                        .map(item -> item.get("projectName") + "(截止" + item.get("contractEndDate") + ")")
                        .collect(Collectors.joining("、"));
                answer.append(" 租入示例：").append(example).append("。");
            }
            if (rentInSummary.unknownEndDateCount > 0) {
                warnings.add("租入有 " + rentInSummary.unknownEndDateCount + " 个进行中项目缺少合同截止日期，无法参与超期判断。");
            }
            if (rentOutSummary.unknownEndDateCount > 0) {
                warnings.add("租出有 " + rentOutSummary.unknownEndDateCount + " 个进行中项目缺少合同截止日期，无法参与超期判断。");
            }
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "project-summary-dual");
        card.put("rentIn", buildBusinessCard(rentInSummary));
        card.put("rentOut", buildBusinessCard(rentOutSummary));
        card.put("anomalyRequested", anomalyRequested);
        card.put("anomalyThreshold", "|本月新增-近3月月均|占比>=60% 且绝对差>=2");

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("project_summary");
        execution.setConfidence(anomalyRequested ? 0.95 : 0.93);
        execution.setAnswer(answer.toString());
        execution.setCards(Collections.singletonList(card));
        execution.setWarnings(warnings);
        execution.setEvidence(buildEvidence(rentInSummary.total + rentOutSummary.total));
        return execution;
    }

    private BusinessSummary buildBusinessSummary(String businessType, boolean overdueRequested) {
        BusinessSummary summary = new BusinessSummary();
        summary.businessType = businessType;

        ProjectListRet ongoingRet = queryProjectList("0", businessType, null);
        ProjectListRet completedRet = queryProjectList("1", businessType, null);

        summary.ongoing = (int) (ongoingRet.getTotalNum() == null ? 0 : ongoingRet.getTotalNum());
        summary.completed = (int) (completedRet.getTotalNum() == null ? 0 : completedRet.getTotalNum());
        summary.total = summary.ongoing + summary.completed;

        if (overdueRequested) {
            OverdueResult overdueResult = detectOverdueProjects(ongoingRet.getProjectBriefInfos());
            summary.overdueProjects = overdueResult.overdueProjects;
            summary.unknownEndDateCount = overdueResult.unknownEndDateCount;
        } else {
            summary.overdueProjects = new ArrayList<>();
            summary.unknownEndDateCount = 0;
        }

        summary.monthStats = analyzeMonthStats(collectAllProjects(ongoingRet, completedRet));
        return summary;
    }

    private List<ProjectListRet.ProjectBriefInfo> collectAllProjects(ProjectListRet ongoingRet, ProjectListRet completedRet) {
        List<ProjectListRet.ProjectBriefInfo> result = new ArrayList<>();
        if (ongoingRet != null && !CollectionUtils.isEmpty(ongoingRet.getProjectBriefInfos())) {
            result.addAll(ongoingRet.getProjectBriefInfos());
        }
        if (completedRet != null && !CollectionUtils.isEmpty(completedRet.getProjectBriefInfos())) {
            result.addAll(completedRet.getProjectBriefInfos());
        }
        return result;
    }

    private Map<String, Object> buildBusinessCard(BusinessSummary summary) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("businessType", summary.businessType);
        card.put("businessLabel", businessLabel(summary.businessType));
        card.put("total", summary.total);
        card.put("ongoing", summary.ongoing);
        card.put("completed", summary.completed);
        card.put("overdueCount", summary.overdueProjects == null ? 0 : summary.overdueProjects.size());
        card.put("overdueProjects", summary.overdueProjects == null
                ? Collections.emptyList()
                : summary.overdueProjects.stream().limit(20).collect(Collectors.toList()));
        card.put("unknownEndDateCount", summary.unknownEndDateCount);
        card.put("monthly", buildMonthlyCard(summary.monthStats));
        return card;
    }

    private Map<String, Object> buildMonthlyCard(MonthStats stats) {
        Map<String, Object> monthly = new LinkedHashMap<>();
        if (stats == null) {
            monthly.put("month", YearMonth.now().toString());
            monthly.put("thisMonthNew", 0);
            monthly.put("lastMonthNew", 0);
            monthly.put("prev3Avg", 0.0);
            monthly.put("anomalyFlag", "normal");
            return monthly;
        }

        monthly.put("month", stats.month);
        monthly.put("thisMonthNew", stats.thisMonthNew);
        monthly.put("lastMonthNew", stats.lastMonthNew);
        monthly.put("prev3Avg", stats.prev3Avg);
        monthly.put("deviationRate", stats.deviationRate);
        monthly.put("monthOverMonthRate", stats.monthOverMonthRate);
        monthly.put("anomalyFlag", stats.anomalyFlag);
        return monthly;
    }

    private MonthStats analyzeMonthStats(List<ProjectListRet.ProjectBriefInfo> projects) {
        MonthStats stats = new MonthStats();
        YearMonth current = YearMonth.now();
        stats.month = current.toString();

        if (CollectionUtils.isEmpty(projects)) {
            stats.thisMonthNew = 0;
            stats.lastMonthNew = 0;
            stats.prev3Avg = 0.0;
            stats.deviationRate = null;
            stats.monthOverMonthRate = null;
            stats.anomalyFlag = "normal";
            return stats;
        }

        Map<YearMonth, Integer> monthCounter = new HashMap<>();
        for (ProjectListRet.ProjectBriefInfo project : projects) {
            LocalDate createDate = parseDate(project == null ? null : project.getCreateDate());
            if (createDate == null) {
                continue;
            }
            YearMonth ym = YearMonth.from(createDate);
            monthCounter.put(ym, monthCounter.getOrDefault(ym, 0) + 1);
        }

        stats.thisMonthNew = monthCounter.getOrDefault(current, 0);
        stats.lastMonthNew = monthCounter.getOrDefault(current.minusMonths(1), 0);

        int prev3Total = 0;
        for (int i = 1; i <= 3; i++) {
            prev3Total += monthCounter.getOrDefault(current.minusMonths(i), 0);
        }
        stats.prev3Avg = prev3Total / 3.0d;

        if (stats.prev3Avg > 0.0d) {
            stats.deviationRate = (stats.thisMonthNew - stats.prev3Avg) / stats.prev3Avg;
        }
        if (stats.lastMonthNew > 0) {
            stats.monthOverMonthRate = (stats.thisMonthNew - stats.lastMonthNew) * 1.0d / stats.lastMonthNew;
        }

        stats.anomalyFlag = detectAnomalyFlag(stats);
        return stats;
    }

    private String detectAnomalyFlag(MonthStats stats) {
        if (stats == null) {
            return "normal";
        }
        if (stats.prev3Avg <= 0.0d) {
            return stats.thisMonthNew >= 3 ? "surge" : "normal";
        }

        if (stats.deviationRate == null) {
            return "normal";
        }

        double absRate = Math.abs(stats.deviationRate);
        double absDiff = Math.abs(stats.thisMonthNew - stats.prev3Avg);
        if (absRate >= 0.6d && absDiff >= 2.0d) {
            return stats.deviationRate >= 0 ? "surge" : "drop";
        }
        return "normal";
    }

    private String buildAnomalySentence(BusinessSummary summary) {
        String label = businessLabel(summary.businessType);
        MonthStats stats = summary.monthStats;
        if (stats == null) {
            return label + "缺少本月统计口径，无法判断异常波动。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(label).append("本月新增 ").append(stats.thisMonthNew)
                .append(" 个（近3月月均 ").append(formatDecimal(stats.prev3Avg))
                .append(" 个");
        if (stats.monthOverMonthRate != null) {
            sb.append("，较上月").append(formatDirectionRate(stats.monthOverMonthRate));
        }
        sb.append("）");

        if ("surge".equals(stats.anomalyFlag)) {
            sb.append("，判定为明显上行波动");
        } else if ("drop".equals(stats.anomalyFlag)) {
            sb.append("，判定为明显下行波动");
        } else {
            sb.append("，暂未触发异常阈值");
        }
        return sb.append("。").toString();
    }

    private String buildCombinedAnomalyConclusion(BusinessSummary rentInSummary, BusinessSummary rentOutSummary) {
        List<String> abnormalSides = new ArrayList<>();
        if (isAnomaly(rentInSummary)) {
            abnormalSides.add("租入");
        }
        if (isAnomaly(rentOutSummary)) {
            abnormalSides.add("租出");
        }

        if (abnormalSides.isEmpty()) {
            return "租入与租出均未触发异常阈值";
        }
        if (abnormalSides.size() == 1) {
            return abnormalSides.get(0) + "侧触发异常阈值";
        }
        return "租入与租出两侧均触发异常阈值";
    }

    private boolean isAnomaly(BusinessSummary summary) {
        if (summary == null || summary.monthStats == null) {
            return false;
        }
        return "surge".equals(summary.monthStats.anomalyFlag) || "drop".equals(summary.monthStats.anomalyFlag);
    }

    private boolean isDualBusinessRequested(String message) {
        return containsAny(message, "租入与租出", "租出与租入", "租入和租出", "租出和租入", "租入及租出", "租出及租入", "租入租出", "双侧", "两侧")
                || (containsAny(message, "租入", "退租") && containsAny(message, "租出", "归还"));
    }

    private String businessLabel(String businessType) {
        return "rent_in".equals(businessType) ? "租入管理" : "租出管理";
    }

    private String formatDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String formatDirectionRate(double rate) {
        String direction = rate >= 0 ? "上升" : "下降";
        double percentage = Math.abs(rate) * 100.0d;
        return direction + String.format(Locale.ROOT, "%.1f", percentage) + "%";
    }

    private AgentEvidence buildEvidence(int recordCount) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + LocalDate.now());
        evidence.setSkills(Collections.singletonList("ProjectRentInsightSkill"));
        evidence.setApiList(Arrays.asList("/project/queryProjectList", "/contract/queryContract"));
        evidence.setRecordCount(recordCount);
        return evidence;
    }

    private ProjectListRet queryProjectList(String statusFlag, String businessType, String keyword) {
        QueryProjectListParam param = new QueryProjectListParam();
        param.setPageNum(1);
        param.setPageSize(DEFAULT_PAGE_SIZE);
        param.setProjectStatusFlag(statusFlag);
        param.setProjectBusinessType(businessType);
        param.setKeyword(keyword);

        ProjectListRet ret = projectService.queryProjectList(param);
        if (ret == null) {
            ret = new ProjectListRet();
            ret.setCurrent(1L);
            ret.setTotalNum(0L);
            ret.setProjectBriefInfos(Collections.emptyList());
            return ret;
        }
        if (ret.getProjectBriefInfos() == null) {
            ret.setProjectBriefInfos(Collections.emptyList());
        }
        return ret;
    }

    private OverdueResult detectOverdueProjects(List<ProjectListRet.ProjectBriefInfo> projects) {
        OverdueResult result = new OverdueResult();
        if (CollectionUtils.isEmpty(projects)) {
            return result;
        }

        LocalDate today = LocalDate.now();
        for (ProjectListRet.ProjectBriefInfo project : projects) {
            ContractInfoRet contractInfo = contractService.queryContract(project.getProjectId());
            LocalDate endDate = parseDate(contractInfo == null ? null : contractInfo.getEndDate());
            if (endDate == null) {
                result.unknownEndDateCount += 1;
                continue;
            }
            if (endDate.isBefore(today)) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("projectId", project.getProjectId());
                info.put("projectName", StringUtils.hasText(project.getProjectName()) ? project.getProjectName() : project.getProjectId());
                info.put("contractEndDate", endDate.toString());
                info.put("overdueDays", (int) (today.toEpochDay() - endDate.toEpochDay()));
                result.overdueProjects.add(info);
            }
        }
        return result;
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value.trim();
        Matcher matcher = DATE_PATTERN.matcher(normalized);
        if (matcher.find()) {
            try {
                int year = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int day = Integer.parseInt(matcher.group(3));
                return LocalDate.of(year, month, day);
            } catch (Exception ignore) {
                // continue fallback parsing
            }
        }

        normalized = normalized.replace('/', '-');
        if (normalized.length() >= 10) {
            normalized = normalized.substring(0, 10);
        }
        try {
            return LocalDate.parse(normalized);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String normalizeBusinessType(String value) {
        if (!StringUtils.hasText(value)) {
            return "rent_out";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "rent_in".equals(normalized) ? "rent_in" : "rent_out";
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.length == 0) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static class OverdueResult {
        private final List<Map<String, Object>> overdueProjects = new ArrayList<>();
        private int unknownEndDateCount;
    }

    private static class BusinessSummary {
        private String businessType;
        private int total;
        private int ongoing;
        private int completed;
        private List<Map<String, Object>> overdueProjects = new ArrayList<>();
        private int unknownEndDateCount;
        private MonthStats monthStats;
    }

    private static class MonthStats {
        private String month;
        private int thisMonthNew;
        private int lastMonthNew;
        private double prev3Avg;
        private Double deviationRate;
        private Double monthOverMonthRate;
        private String anomalyFlag;
    }
}
