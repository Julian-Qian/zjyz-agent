package com.zjyz.agent.workspace.model;

import lombok.Data;

import java.util.Collections;
import java.util.List;

public final class AgentWorkspaceRequests {
    private AgentWorkspaceRequests() {
    }

    @Data
    public static class CreateWorkspace {
        private String scopeType;
        private String projectId;
    }

    @Data
    public static class CreateThread {
        private String title;
    }

    @Data
    public static class UpdateThread {
        private String title;
    }

    @Data
    public static class CreateRun {
        private String message;
        private String clientRequestId;
        private String selectionMode;
        private List<String> projectIds = Collections.emptyList();
        private String preferredSkillCode;
        private List<String> attachmentIds = Collections.emptyList();
    }
}
