package com.zjyz.agent.workspace.v2.service;

import com.zjyz.agent.workspace.v2.controller.AgentV2Controller;
import com.zjyz.agent.workspace.v2.service.AgentV2CoordinatorService;
import com.zjyz.agent.workspace.v2.service.AgentV2InteractionService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentV2ControllerContractTest {

    @Test
    void canonicalSelectionModeTakesPrecedenceOverLegacyScopeMode() {
        AgentV2CoordinatorService coordinator = mock(AgentV2CoordinatorService.class);
        AgentV2Controller controller = new AgentV2Controller(coordinator,
                mock(AgentV2InteractionService.class));

        controller.capabilities("ALL", "EXPLICIT", Arrays.asList("p1", "p2"));

        verify(coordinator).capabilities("ALL", Arrays.asList("p1", "p2"));
    }

    @Test
    void taskCancelUsesV2TaskLifecycleEndpoint() {
        AgentV2CoordinatorService coordinator = mock(AgentV2CoordinatorService.class);
        AgentV2Controller controller = new AgentV2Controller(coordinator,
                mock(AgentV2InteractionService.class));

        controller.cancelTask("task-1");

        verify(coordinator).cancelTask("task-1");
    }
}
