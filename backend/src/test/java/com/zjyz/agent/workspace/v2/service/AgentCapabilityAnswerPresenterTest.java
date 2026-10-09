package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentCapabilityAnswerPresenterTest {
    @Test void introductionUsesExamplesAndHidesInternalNamesAndUnavailableFeatures() {
        String answer = AgentCapabilityAnswerPresenter.present(Map.of("capabilities", List.of(
            Map.of("code", "help.search", "name", "INTERNAL_HELP", "available", true),
            Map.of("code", "project.list", "name", "INTERNAL_LIST", "available", true),
            Map.of("code", "project.reconciliation_due", "available", false))));
        assertTrue(answer.contains("怎么新建租出项目"));
        assertTrue(answer.contains("小何"));
        assertFalse(answer.contains("本月"));
        assertFalse(answer.contains("INTERNAL"));
        assertFalse(answer.contains("help.search"));
        assertFalse(answer.contains("合同"));
    }
    @Test void typedCapabilitiesAndDocumentFeatureAreRespected() {
        AgentV2Models.Capability capability = new AgentV2Models.Capability();
        capability.setCode("project.material_occupancy"); capability.setAvailable(true);
        String answer = AgentCapabilityAnswerPresenter.present(Map.of("capabilities", List.of(capability),
            "conversationFeatures", Map.of("documentReview", true)));
        assertTrue(answer.contains("没归还")); assertTrue(answer.contains("上传的合同"));
        assertFalse(answer.contains("新建租出"));
    }
    @Test void emptyAvailabilityDoesNotPromiseWork() {
        assertTrue(AgentCapabilityAnswerPresenter.present(Map.of()).contains("没有可用"));
    }
}
