package com.zjyz.agent.orch;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentSkillRegistry {
    private final Map<AgentIntentType, AgentSkillHandler> intentSkillMap = new EnumMap<>(AgentIntentType.class);

    @Autowired
    public AgentSkillRegistry(List<AgentSkillHandler> handlers) {
        if (handlers == null) {
            return;
        }
        for (AgentSkillHandler handler : handlers) {
            intentSkillMap.put(handler.supportedIntent(), handler);
        }
    }

    public AgentSkillHandler resolve(AgentIntentType intentType) {
        return intentSkillMap.get(intentType);
    }
}
