package com.zjyz.agent.workspace.v2.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.pojo.entity.ProjectEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves every V2 request to an explicit immutable set of authorized projects. */
@Service
public class AgentV2ScopeService {
    private final ProjectMapper projectMapper;
    private final ObjectMapper objectMapper;

    @Value("${agent.v2.scope.maxFrozenProjects:2000}")
    private int maxFrozenProjects;

    public AgentV2ScopeService(ProjectMapper projectMapper, ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.objectMapper = objectMapper;
    }

    public AgentV2Models.ScopeSnapshot freeze(AgentRuntimeRecords.Workspace workspace,
                                              AgentV2Models.CreateTurnRequest request,
                                              String cid) {
        if (workspace == null) {
            throw new MyBizException("任务上下文缺失，请重新发起任务", "AGT400");
        }
        if (!"TENANT".equalsIgnoreCase(workspace.getScopeType())) {
            ProjectEntity project = requireProject(cid, workspace.getProjectId());
            AgentV2Models.ScopeSnapshot fixed = snapshot("PROJECT", "EXPLICIT",
                    Collections.singletonList(project.getProjectId()), cid);
            fixed.setExplicitOverride(true);
            return fixed;
        }

        AgentV2Models.ScopeSelection selection = request == null ? null : request.getScopeSelection();
        String mode = selection == null ? request == null ? null : request.getSelectionMode()
                : selection.getSelectionMode();
        List<String> selectedProjectIds = selection == null ? request == null ? Collections.emptyList()
                : request.getProjectIds() : selection.getProjectIds();
        if (!StringUtils.hasText(mode)) {
            throw new MyBizException("项目选择模式不能为空", "AGT400");
        }
        String normalizedMode = mode.trim().toUpperCase(Locale.ROOT);
        if (!"ALL".equals(normalizedMode) && !"EXPLICIT".equals(normalizedMode)) {
            throw new MyBizException("项目选择模式无效", "AGT400");
        }

        List<String> frozenIds;
        if ("ALL".equals(normalizedMode)) {
            if (selectedProjectIds != null && !selectedProjectIds.isEmpty()) {
                throw new MyBizException("选择全部项目时不能再指定单个项目", "AGT400");
            }
            frozenIds = projectMapper.selectList(new QueryWrapper<ProjectEntity>()
                            .eq("cid", cid))
                    .stream()
                    .map(ProjectEntity::getProjectId)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        } else {
            Set<String> normalizedIds = selectedProjectIds == null ? Collections.emptySet()
                    : selectedProjectIds.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (normalizedIds.isEmpty()) {
                throw new MyBizException("请至少选择一个项目", "AGT400");
            }
            List<ProjectEntity> projects = projectMapper.selectList(new QueryWrapper<ProjectEntity>()
                    .eq("cid", cid)
                    .in("project_id", normalizedIds));
            Set<String> allowedIds = projects.stream()
                    .map(ProjectEntity::getProjectId)
                    .collect(Collectors.toSet());
            if (allowedIds.size() != normalizedIds.size() || !allowedIds.containsAll(normalizedIds)) {
                throw new MyBizException("项目范围包含不存在或无权限的项目", "AGT403");
            }
            frozenIds = new ArrayList<>(normalizedIds);
            frozenIds.sort(Comparator.naturalOrder());
        }
        if (frozenIds.size() > Math.max(maxFrozenProjects, 100)) {
            throw new MyBizException("项目过多，请改为明确选择项目", "AGT400");
        }
        AgentV2Models.ScopeSnapshot snapshot = snapshot("TENANT", normalizedMode, frozenIds, cid);
        snapshot.setExplicitOverride(selection != null && Boolean.TRUE.equals(selection.getExplicitOverride()));
        return snapshot;
    }

    public AgentV2Models.ScopeSnapshot attachConversationContext(AgentV2Models.ScopeSnapshot candidate,
                                                                 AgentV2Models.Task previousTask) {
        if (candidate == null || previousTask == null || !StringUtils.hasText(previousTask.getScopeJson())) {
            return candidate;
        }
        try {
            AgentV2Models.ScopeSnapshot previous = objectMapper.readValue(
                    previousTask.getScopeJson(), AgentV2Models.ScopeSnapshot.class);
            candidate.setContextTaskId(previousTask.getTaskId());
            candidate.setInheritedRequestedSelectionMode(previous.getRequestedSelectionMode());
            candidate.setInheritedProjectIds(previous.getProjectIds() == null
                    ? Collections.emptyList() : new ArrayList<>(previous.getProjectIds()));
            candidate.setInheritedScopeHash(previous.getScopeHash());
        } catch (Exception ignored) {
            // The current candidate remains authoritative if an older task has an unreadable snapshot.
        }
        return candidate;
    }

    public AgentV2Models.ScopeSnapshot resolveForRelation(AgentV2Models.ScopeSnapshot snapshot,
                                                          String relationType) {
        if (snapshot == null) {
            throw new MyBizException("任务项目范围缺失，请重新选择项目", "AGT403");
        }
        boolean related = "CONTINUE".equals(relationType) || "CORRECT".equals(relationType)
                || "CLARIFICATION_RESPONSE".equals(relationType);
        if (related && !snapshot.isExplicitOverride() && snapshot.getContextTaskId() != null
                && snapshot.getInheritedProjectIds() != null) {
            snapshot.setRequestedSelectionMode(snapshot.getInheritedRequestedSelectionMode());
            snapshot.setProjectIds(new ArrayList<>(snapshot.getInheritedProjectIds()));
            snapshot.setScopeHash(snapshot.getInheritedScopeHash());
        } else {
            snapshot.setRequestedSelectionMode(snapshot.getCandidateRequestedSelectionMode());
            snapshot.setProjectIds(new ArrayList<>(snapshot.getCandidateProjectIds()));
            snapshot.setScopeHash(snapshot.getCandidateScopeHash());
        }
        snapshot.setProjectCount(snapshot.getProjectIds().size());
        snapshot.setEffectiveSelectionMode("EXPLICIT");
        return snapshot;
    }

    public AgentRuntimeRecords.Workspace apply(AgentRuntimeRecords.Workspace source,
                                               AgentV2Models.ScopeSnapshot snapshot,
                                               String cid) {
        if (source == null || snapshot == null || !snapshot.isFrozen()
                || snapshot.getProjectIds() == null) {
            throw new MyBizException("任务项目范围缺失，请重新选择项目", "AGT403");
        }
        if (snapshot.getProjectIds().isEmpty()) {
            source.setSelectionMode("EXPLICIT");
            source.setProjectIds(Collections.emptyList());
            source.setProjectId(null);
            source.setProjectBusinessType("mixed");
            source.setName("当前无项目");
            return source;
        }
        List<ProjectEntity> projects = projectMapper.selectList(new QueryWrapper<ProjectEntity>()
                .eq("cid", cid)
                .in("project_id", snapshot.getProjectIds()));
        Set<String> currentIds = projects.stream().map(ProjectEntity::getProjectId).collect(Collectors.toSet());
        if (currentIds.size() != snapshot.getProjectIds().size()
                || !currentIds.containsAll(snapshot.getProjectIds())) {
            throw new MyBizException("项目范围包含不可访问项目", "AGT403");
        }

        // V2 deliberately presents ALL as an explicit snapshot. A project created after submission
        // cannot enter this Run, and tools that only support dynamic ALL are therefore not exposed.
        source.setSelectionMode("EXPLICIT");
        source.setProjectIds(new ArrayList<>(snapshot.getProjectIds()));
        if (projects.size() == 1) {
            ProjectEntity project = projects.get(0);
            source.setProjectId(project.getProjectId());
            source.setProjectBusinessType(project.getProjectBusinessType());
            source.setName(project.getProjectName());
        } else {
            source.setProjectId(null);
            source.setProjectBusinessType("mixed");
            source.setName("已冻结" + projects.size() + "个项目");
        }
        return source;
    }

    private AgentV2Models.ScopeSnapshot snapshot(String scopeType,
                                                 String requestedMode,
                                                 List<String> projectIds,
                                                 String cid) {
        AgentV2Models.ScopeSnapshot snapshot = new AgentV2Models.ScopeSnapshot();
        snapshot.setScopeType(scopeType);
        snapshot.setRequestedSelectionMode(requestedMode);
        snapshot.setEffectiveSelectionMode("EXPLICIT");
        snapshot.setProjectIds(Collections.unmodifiableList(new ArrayList<>(projectIds)));
        snapshot.setProjectCount(projectIds.size());
        snapshot.setFrozen(true);
        snapshot.setFrozenAt(LocalDateTime.now());
        snapshot.setScopeHash(sha256(cid + "|" + String.join(",", projectIds)));
        snapshot.setCandidateRequestedSelectionMode(requestedMode);
        snapshot.setCandidateProjectIds(new ArrayList<>(projectIds));
        snapshot.setCandidateScopeHash(snapshot.getScopeHash());
        return snapshot;
    }

    private ProjectEntity requireProject(String cid, String projectId) {
        if (!StringUtils.hasText(projectId)) {
            throw new MyBizException("任务缺少项目，请重新选择项目", "AGT400");
        }
        ProjectEntity project = projectMapper.selectOne(new QueryWrapper<ProjectEntity>()
                .eq("cid", cid)
                .eq("project_id", projectId)
                .last("LIMIT 1"));
        if (project == null) {
            throw new MyBizException("项目不存在或无权限", "PRCT404");
        }
        return project;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("无法生成项目范围摘要", error);
        }
    }
}
