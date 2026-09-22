package com.zjyz.agent.workspace.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Strict parser for the project scope snapshot persisted on the run-bound user message. */
public final class AgentRunProjectScopeSelection {
    private final String selectionMode;
    private final List<String> projectIds;

    private AgentRunProjectScopeSelection(String selectionMode, List<String> projectIds) {
        this.selectionMode = selectionMode;
        this.projectIds = Collections.unmodifiableList(new ArrayList<>(projectIds));
    }

    public static AgentRunProjectScopeSelection parse(ObjectMapper objectMapper,
                                                      AgentRuntimeRecords.Message userMessage) {
        if (objectMapper == null || userMessage == null || !StringUtils.hasText(userMessage.getMetadataJson())) {
            throw invalid();
        }
        try {
            JsonNode metadata = objectMapper.readTree(userMessage.getMetadataJson());
            JsonNode scope = metadata == null ? null : metadata.get("projectScope");
            if (metadata == null || !metadata.isObject() || scope == null || !scope.isObject()) {
                throw invalid();
            }
            JsonNode modeNode = scope.get("selectionMode");
            if (modeNode == null || !modeNode.isTextual() || !StringUtils.hasText(modeNode.asText())) {
                throw invalid();
            }
            String mode = modeNode.asText().trim().toUpperCase(Locale.ROOT);
            if (!"ALL".equals(mode) && !"EXPLICIT".equals(mode)) {
                throw invalid();
            }
            JsonNode idsNode = scope.get("projectIds");
            if (idsNode != null && !idsNode.isArray()) {
                throw invalid();
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            if (idsNode != null) {
                for (JsonNode node : idsNode) {
                    if (!node.isTextual() || !StringUtils.hasText(node.asText())) {
                        throw invalid();
                    }
                    ids.add(node.asText().trim());
                }
            }
            if ("ALL".equals(mode) && !ids.isEmpty()) {
                throw invalid();
            }
            if ("EXPLICIT".equals(mode) && ids.isEmpty()) {
                throw invalid();
            }
            return new AgentRunProjectScopeSelection(mode, new ArrayList<>(ids));
        } catch (MyBizException error) {
            throw error;
        } catch (Exception error) {
            throw invalid();
        }
    }

    public String getSelectionMode() {
        return selectionMode;
    }

    public List<String> getProjectIds() {
        return projectIds;
    }

    private static MyBizException invalid() {
        return new MyBizException("项目范围快照缺失或损坏", "AGT400");
    }
}
