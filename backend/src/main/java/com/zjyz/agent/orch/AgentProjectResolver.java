package com.zjyz.agent.orch;

import com.zjyz.pojo.param.req.QueryProjectListParam;
import com.zjyz.pojo.param.ret.ProjectListRet;
import com.zjyz.service.ProjectService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AgentProjectResolver {
    private static final int DEFAULT_PAGE_SIZE = 10_000;
    private static final Pattern PROJECT_NO_PATTERN = Pattern.compile("\\b\\d+(?:-[0-9A-Za-z]+)+\\b");

    @Autowired
    private ProjectService projectService;

    public String resolveProjectId(AgentSlotBag slots, String message) {
        if (slots == null) {
            return null;
        }
        if (StringUtils.hasText(slots.getProjectId())) {
            return slots.getProjectId();
        }

        String businessType = normalizeBusinessType(slots.getProjectBusinessType());
        List<ProjectListRet.ProjectBriefInfo> preferredProjects = queryAllProjects(businessType);
        List<ProjectListRet.ProjectBriefInfo> secondaryProjects = queryAllProjects(otherBusinessType(businessType));
        List<ProjectListRet.ProjectBriefInfo> allProjects = new ArrayList<>(preferredProjects);
        allProjects.addAll(secondaryProjects);

        if (slots.getProjectOrdinal() != null && slots.getProjectOrdinal() > 0) {
            int idx = slots.getProjectOrdinal() - 1;
            if (idx < preferredProjects.size()) {
                return preferredProjects.get(idx).getProjectId();
            }
        }

        String keyword = slots.getProjectKeyword();
        if (!StringUtils.hasText(keyword)) {
            keyword = extractKeywordByContains(message, allProjects);
        }
        if (StringUtils.hasText(keyword)) {
            // 先在已加载的项目列表中做规范化匹配，避免 SQL like 在不同命名格式下漏匹配。
            String projectId = resolveByProjectListKeyword(keyword, preferredProjects);
            if (!StringUtils.hasText(projectId)) {
                projectId = resolveByProjectListKeyword(keyword, secondaryProjects);
            }
            if (StringUtils.hasText(projectId)) {
                return projectId;
            }

            String dbProjectId = resolveByKeyword(businessType, keyword);
            if (!StringUtils.hasText(dbProjectId)) {
                dbProjectId = resolveByKeyword(otherBusinessType(businessType), keyword);
            }
            if (StringUtils.hasText(dbProjectId)) {
                return dbProjectId;
            }
        }

        String projectNo = extractProjectNo(message);
        if (StringUtils.hasText(projectNo)) {
            String projectId = resolveByProjectListKeyword(projectNo, allProjects);
            if (StringUtils.hasText(projectId)) {
                return projectId;
            }
        }

        String projectId = resolveByContainsAcrossBusinessType(message, businessType);
        if (StringUtils.hasText(projectId)) {
            return projectId;
        }

        return null;
    }

    private String resolveByContainsAcrossBusinessType(String message, String preferredBusinessType) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        List<ProjectListRet.ProjectBriefInfo> preferred = queryAllProjects(preferredBusinessType);
        String keyword = extractKeywordByContains(message, preferred);
        if (StringUtils.hasText(keyword)) {
            String projectId = resolveByKeyword(preferredBusinessType, keyword);
            if (StringUtils.hasText(projectId)) {
                return projectId;
            }
        }

        String secondaryBusinessType = otherBusinessType(preferredBusinessType);
        List<ProjectListRet.ProjectBriefInfo> secondary = queryAllProjects(secondaryBusinessType);
        keyword = extractKeywordByContains(message, secondary);
        if (StringUtils.hasText(keyword)) {
            return resolveByKeyword(secondaryBusinessType, keyword);
        }
        return null;
    }

    private String resolveByProjectListKeyword(String keyword, List<ProjectListRet.ProjectBriefInfo> projects) {
        if (!StringUtils.hasText(keyword) || CollectionUtils.isEmpty(projects)) {
            return null;
        }
        String normalizedKeyword = normalizeForLooseMatch(keyword);
        if (!StringUtils.hasText(normalizedKeyword)) {
            return null;
        }

        ProjectListRet.ProjectBriefInfo best = null;
        int bestScore = -1;
        for (ProjectListRet.ProjectBriefInfo item : projects) {
            if (item == null || !StringUtils.hasText(item.getProjectName()) || !StringUtils.hasText(item.getProjectId())) {
                continue;
            }
            String normalizedName = normalizeForLooseMatch(item.getProjectName());
            if (!StringUtils.hasText(normalizedName)) {
                continue;
            }
            boolean match = normalizedName.contains(normalizedKeyword) || normalizedKeyword.contains(normalizedName);
            if (!match) {
                continue;
            }
            int score = Math.min(normalizedName.length(), normalizedKeyword.length());
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best == null ? null : best.getProjectId();
    }

    private String resolveByKeyword(String businessType, String keyword) {
        ProjectListRet ongoing = queryProjectList("0", businessType, keyword);
        if (!CollectionUtils.isEmpty(ongoing.getProjectBriefInfos())) {
            return ongoing.getProjectBriefInfos().get(0).getProjectId();
        }
        ProjectListRet completed = queryProjectList("1", businessType, keyword);
        if (!CollectionUtils.isEmpty(completed.getProjectBriefInfos())) {
            return completed.getProjectBriefInfos().get(0).getProjectId();
        }
        return null;
    }

    private String extractKeywordByContains(String message, List<ProjectListRet.ProjectBriefInfo> projects) {
        if (!StringUtils.hasText(message) || CollectionUtils.isEmpty(projects)) {
            return null;
        }
        String normalizedMessage = normalizeForMatch(message);
        ProjectListRet.ProjectBriefInfo best = null;
        int bestLen = -1;
        for (ProjectListRet.ProjectBriefInfo item : projects) {
            if (item == null || !StringUtils.hasText(item.getProjectName())) {
                continue;
            }
            String normalizedName = normalizeForMatch(item.getProjectName());
            if (!StringUtils.hasText(normalizedName) || !normalizedMessage.contains(normalizedName)) {
                continue;
            }
            if (normalizedName.length() > bestLen) {
                best = item;
                bestLen = normalizedName.length();
            }
        }
        return best == null ? null : best.getProjectName();
    }

    private List<ProjectListRet.ProjectBriefInfo> queryAllProjects(String businessType) {
        List<ProjectListRet.ProjectBriefInfo> all = new ArrayList<>();
        all.addAll(queryProjectList("0", businessType, null).getProjectBriefInfos());
        all.addAll(queryProjectList("1", businessType, null).getProjectBriefInfos());
        all.sort(Comparator.comparing(ProjectListRet.ProjectBriefInfo::getCreateDate,
                Comparator.nullsLast(String::compareTo)).reversed());
        return all;
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

    private String normalizeBusinessType(String projectBusinessType) {
        if (!StringUtils.hasText(projectBusinessType)) {
            return "rent_out";
        }
        String normalized = projectBusinessType.trim().toLowerCase(Locale.ROOT);
        return "rent_in".equals(normalized) ? "rent_in" : "rent_out";
    }

    private String otherBusinessType(String businessType) {
        return "rent_in".equalsIgnoreCase(businessType) ? "rent_out" : "rent_in";
    }

    private String normalizeForMatch(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            boolean keep = Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || (ch >= '\u4e00' && ch <= '\u9fa5');
            if (keep) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private String normalizeForLooseMatch(String value) {
        String normalized = normalizeForMatch(value);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        return normalized.replace("-", "").replace("_", "");
    }

    private String extractProjectNo(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        Matcher matcher = PROJECT_NO_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
