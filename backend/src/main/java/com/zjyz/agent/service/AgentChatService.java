package com.zjyz.agent.service;

import com.zjyz.agent.model.AgentChatRequest;
import com.zjyz.agent.model.AgentChatResponse;

public interface AgentChatService {
    AgentChatResponse chat(AgentChatRequest request);
}
