package com.zjyz.agent.orch;

import java.util.Map;

public interface AgentSkillHandler {
    AgentIntentType supportedIntent();

    default boolean requiresProjectId(AgentSlotBag slots) {
        return false;
    }

    AgentSkillExecution execute(String message, Map<String, Object> context, AgentSlotBag slots);
}
