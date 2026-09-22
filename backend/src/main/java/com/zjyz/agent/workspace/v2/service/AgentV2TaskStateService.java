package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.common.exception.MyBizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transactional Task lifecycle boundary; Run remains a short execution record. */
@Service
public class AgentV2TaskStateService {
    private final AgentV2Mapper mapper;
    private final ObjectMapper objectMapper;

    public AgentV2TaskStateService(AgentV2Mapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void persistClarification(AgentV2Models.Task task,
                                     AgentV2Models.Interaction interaction) {
        mapper.insertInteraction(interaction);
        if (mapper.transitionTaskStatus(task.getTaskId(), "READY", "WAITING_USER",
                LocalDateTime.now(), null) <= 0) {
            throw new MyBizException("任务状态已变化，无法创建重复澄清", "AGT409");
        }
        task.setStatus("WAITING_USER");
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentV2Models.Task cancel(AgentV2Models.Task task) {
        if (task == null) {
            throw new MyBizException("任务不存在", "AGT404");
        }
        if (isTaskTerminal(task.getStatus())) {
            return task;
        }
        AgentV2Models.RunState run = mapper.selectRunState(task.getLatestRunId());
        if (run == null) {
            throw new MyBizException("任务执行不存在", "AGT404");
        }
        LocalDateTime now = LocalDateTime.now();
        boolean runCancelled = mapper.cancelActiveRun(run.getRunId(), task.getTaskId(),
                task.getCid(), task.getOwnerUid(), now) > 0;
        if (!runCancelled && !isTerminal(run.getStatus())) {
            throw new MyBizException("任务正在完成终态写入，请稍后刷新", "AGT409");
        }
        int taskCancelled = mapper.transitionTaskStatus(task.getTaskId(), task.getStatus(),
                "CANCELLED", now, now);
        if (taskCancelled <= 0) {
            AgentV2Models.Task refreshed = mapper.selectTask(task.getTaskId());
            if (refreshed != null && !isTaskTerminal(refreshed.getStatus())) {
                taskCancelled = mapper.transitionTaskStatus(task.getTaskId(), refreshed.getStatus(),
                        "CANCELLED", now, now);
            }
        }
        if (taskCancelled <= 0) {
            throw new MyBizException("任务状态已变化，取消未生效", "AGT409");
        }
        mapper.cancelPendingInteractions(task.getTaskId(), now);
        // A completed clarification Run is immutable history. Cancelling its WAITING_USER Task must
        // not rewrite that Run's outcome; only a Run actually won by cancel receives cancel outcome.
        if (runCancelled && mapper.updateRunOutcome(run.getRunId(),
                outcome(task.getTaskId(), "CANCELLED")) <= 0) {
            throw new MyBizException("取消结果保存失败", "AGT500");
        }
        return mapper.selectTask(task.getTaskId());
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentV2Models.Task reconcile(AgentV2Models.Task task) {
        if (task == null || isTaskTerminal(task.getStatus())) {
            return task;
        }
        AgentV2Models.RunState run = mapper.selectRunState(task.getLatestRunId());
        if (run == null || !isTerminal(run.getStatus())) {
            return task;
        }
        AgentV2Models.Interaction interaction = mapper.selectLatestInteraction(task.getTaskId());
        boolean pendingInteraction = interaction != null && "PENDING".equals(interaction.getStatus());
        String target;
        if ("CANCELLED".equals(run.getStatus())) {
            target = "CANCELLED";
        } else if ("FAILED".equals(run.getStatus()) || "INTERRUPTED".equals(run.getStatus())) {
            target = "BLOCKED";
        } else if (pendingInteraction) {
            target = "WAITING_USER";
        } else {
            target = outcomeTaskStatus(run.getOutcomeJson());
            if (!"COMPLETED".equals(target) && !"BLOCKED".equals(target)
                    && !"CANCELLED".equals(target)) {
                target = "COMPLETED";
            }
        }
        if (!target.equals(task.getStatus())) {
            LocalDateTime completedAt = "WAITING_USER".equals(target) ? null : LocalDateTime.now();
            mapper.transitionTaskStatus(task.getTaskId(), task.getStatus(), target,
                    LocalDateTime.now(), completedAt);
        }
        if (!"WAITING_USER".equals(target)) {
            mapper.cancelPendingInteractions(task.getTaskId(), LocalDateTime.now());
        }
        return mapper.selectTask(task.getTaskId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void block(String taskId) {
        AgentV2Models.Task task = mapper.selectTask(taskId);
        if (task == null || isTaskTerminal(task.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        mapper.transitionTaskStatus(taskId, task.getStatus(), "BLOCKED", now, now);
        mapper.cancelPendingInteractions(taskId, now);
    }

    private String outcomeTaskStatus(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            Object value = objectMapper.readValue(json, Map.class).get("taskStatus");
            return value == null ? null : String.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String outcome(String taskId, String status) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", taskId);
        value.put("taskStatus", status);
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new MyBizException("任务终态保存失败", "AGT500");
        }
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)
                || "INTERRUPTED".equals(status);
    }

    private boolean isTaskTerminal(String status) {
        return "COMPLETED".equals(status) || "BLOCKED".equals(status) || "CANCELLED".equals(status);
    }
}
