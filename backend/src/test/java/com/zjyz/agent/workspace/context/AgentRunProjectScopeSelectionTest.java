package com.zjyz.agent.workspace.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRunProjectScopeSelectionTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsMissingDamagedAndUnknownRunBoundScope() {
        assertAg400(message(null));
        assertAg400(message("{}"));
        assertAg400(message("{\"projectScope\":{}}"));
        assertAg400(message("{\"projectScope\":{\"selectionMode\":\" \",\"projectIds\":[]}}"));
        assertAg400(message("{\"projectScope\":{\"selectionMode\":\"SINGLE\",\"projectIds\":[]}}"));
    }

    @Test
    void rejectsAllScopeThatCarriesProjectIds() {
        assertAg400(message("{\"projectScope\":{\"selectionMode\":\"ALL\",\"projectIds\":[\"p-1\"]}}"));
    }

    @Test
    void parsesExplicitFrozenProjectIds() {
        AgentRunProjectScopeSelection scope = AgentRunProjectScopeSelection.parse(objectMapper,
                message("{\"projectScope\":{\"selectionMode\":\"EXPLICIT\",\"projectIds\":[\"p-1\"]}}"));

        assertEquals("EXPLICIT", scope.getSelectionMode());
        assertEquals(Collections.singletonList("p-1"), scope.getProjectIds());
    }

    private void assertAg400(AgentRuntimeRecords.Message message) {
        MyBizException error = assertThrows(MyBizException.class,
                () -> AgentRunProjectScopeSelection.parse(objectMapper, message));
        assertEquals("AGT400", error.getErrorCode());
    }

    private AgentRuntimeRecords.Message message(String metadata) {
        AgentRuntimeRecords.Message message = new AgentRuntimeRecords.Message();
        message.setMetadataJson(metadata);
        return message;
    }
}
