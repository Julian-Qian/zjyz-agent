package com.zjyz.agent.workspace.knowledge;

import com.zjyz.agent.workspace.context.AgentTaskFrame;

public interface AgentKnowledgeRetriever {
    AgentKnowledgeContext retrieve(AgentTaskFrame taskFrame, int limit);
}
