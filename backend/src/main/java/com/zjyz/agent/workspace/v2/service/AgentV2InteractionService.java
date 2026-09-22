package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.dao.AgentV2Mapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.common.security.AuthContext;
import com.zjyz.common.util.CommonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentV2InteractionService {
    private final AgentV2Mapper mapper;
    private final AgentV2CoordinatorService coordinator;
    private final ObjectMapper objectMapper;

    public AgentV2InteractionService(AgentV2Mapper mapper,
                                     AgentV2CoordinatorService coordinator,
                                     ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.coordinator = coordinator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> answer(String interactionId,
                                      AgentV2Models.AnswerInteractionRequest request) {
        coordinator.requireV2Enabled();
        String cid = requireCid();
        String uid = requireUid();
        String clientRequestId = request == null ? null : request.getClientRequestId();
        if (!StringUtils.hasText(clientRequestId)) {
            throw new MyBizException("回答澄清时缺少请求标识，请重新提交", "AGT400");
        }
        clientRequestId = clientRequestId.trim();
        // A locking read gives concurrent retries current committed state under MySQL's default
        // REPEATABLE READ isolation, rather than an older snapshot of PENDING.
        AgentV2Models.Interaction interaction = mapper.selectInteractionForUpdate(interactionId);
        if (interaction == null || !cid.equals(interaction.getCid()) || !uid.equals(interaction.getOwnerUid())) {
            throw new MyBizException("澄清交互不存在或无权限", "AGT404");
        }
        if ("ANSWERED".equals(interaction.getStatus())
                && clientRequestId.equals(interaction.getAnswerClientRequestId())
                && StringUtils.hasText(interaction.getAnsweredTaskId())) {
            return coordinator.getTask(interaction.getAnsweredTaskId());
        }
        if (!"PENDING".equals(interaction.getStatus())) {
            throw new MyBizException("该澄清已经回答或失效", "AGT409");
        }
        AgentV2Models.Task parent = coordinator.requireTaskAccess(interaction.getTaskId());
        String message = answerText(request);
        if (!StringUtils.hasText(message)) {
            throw new MyBizException("请输入澄清内容", "AGT400");
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("message", message);
        answer.put("value", request == null ? Collections.emptyMap() : request.getAnswer());
        if (mapper.claimInteractionAnswer(interactionId, cid, uid, clientRequestId,
                toJson(answer), LocalDateTime.now()) <= 0) {
            throw new MyBizException("该澄清已经回答或失效", "AGT409");
        }

        AgentV2Models.ScopeSnapshot parentScope = readScope(parent.getScopeJson());
        AgentV2Models.ScopeSelection selection = new AgentV2Models.ScopeSelection();
        selection.setSelectionMode("EXPLICIT");
        selection.setProjectIds(parentScope.getProjectIds());
        selection.setExplicitOverride(false);
        AgentV2Models.CreateTurnRequest turn = new AgentV2Models.CreateTurnRequest();
        turn.setMessage(message.trim());
        turn.setClientRequestId(clientRequestId);
        turn.setScopeSelection(selection);
        Map<String, Object> context = new LinkedHashMap<>();
        if (request != null && request.getContext() != null) {
            context.putAll(request.getContext());
        }
        context.put("interactionId", interactionId);
        context.put("interactionPrompt", interaction.getPromptText());
        turn.setContext(context);
        LocalDateTime now = LocalDateTime.now();
        if (mapper.transitionTaskStatus(parent.getTaskId(), "WAITING_USER", "COMPLETED", now, now) <= 0) {
            throw new MyBizException("原任务状态已变化，澄清回答未生效", "AGT409");
        }
        Map<String, Object> child = coordinator.createTurnFromInteraction(
                parent.getThreadId(), turn, parent.getTaskId());
        Object childTaskId = child.get("taskId");
        if (childTaskId == null || !StringUtils.hasText(String.valueOf(childTaskId))) {
            throw new MyBizException("澄清回答未能创建后续任务", "AGT500");
        }
        if (mapper.completeInteractionAnswer(interactionId, cid, uid, clientRequestId,
                String.valueOf(childTaskId), now) <= 0) {
            throw new MyBizException("澄清回答状态已变化，请刷新后重试", "AGT409");
        }
        return child;
    }

    private String answerText(AgentV2Models.AnswerInteractionRequest request) {
        if (request == null) {
            return null;
        }
        if (StringUtils.hasText(request.getMessage())) {
            return request.getMessage().trim();
        }
        if (request.getAnswer() != null) {
            Object value = request.getAnswer().get("value");
            if (value == null) {
                value = request.getAnswer().get("label");
            }
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private AgentV2Models.ScopeSnapshot readScope(String json) {
        try {
            return objectMapper.readValue(json, AgentV2Models.ScopeSnapshot.class);
        } catch (Exception error) {
            throw new MyBizException("原任务项目范围异常，请重新发起任务", "AGT500");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new MyBizException("澄清回答保存失败", "AGT500");
        }
    }

    private String requireUid() {
        String uid = AuthContext.getUid();
        if (!StringUtils.hasText(uid)) {
            throw new MyBizException("未登录或登录已失效", "AUTH401");
        }
        return uid;
    }

    private String requireCid() {
        String cid = AuthContext.getCid();
        if (!StringUtils.hasText(cid)) {
            cid = CommonUtil.getCid();
        }
        return cid;
    }
}
