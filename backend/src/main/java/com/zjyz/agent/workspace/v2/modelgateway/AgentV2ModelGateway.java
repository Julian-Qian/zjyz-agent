package com.zjyz.agent.workspace.v2.modelgateway;

import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;

import java.util.List;
import java.util.Map;

/**
 * V2-only model boundary. It intentionally does not extend {@link AgentModelGateway}, so adding
 * V2 provider routing cannot change the legacy V1 gateway bean selected by Spring.
 */
public interface AgentV2ModelGateway {
    boolean isAvailable();

    default AgentModelGateway.ModelResult interpret(List<Map<String, Object>> messages) {
        return complete(messages, java.util.Collections.emptyList(), "low");
    }


    AgentModelGateway.ModelResult complete(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           String reasoningEffort);
}
