package com.zjyz.agent.workspace.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.context.AgentTaskFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInAgentKnowledgeRetrieverTest {

    @Test
    void retrievesApprovedSettlementSemanticAndToolHint() {
        BuiltInAgentKnowledgeRetriever retriever = new BuiltInAgentKnowledgeRetriever(new ObjectMapper());
        AgentTaskFrame frame = new AgentTaskFrame();
        frame.setDomain("SETTLEMENT");
        frame.setUserGoal("8月份有哪些项目应该进行对账了？");
        frame.setRequiresBusinessData(true);

        AgentKnowledgeContext context = retriever.retrieve(frame, 5);

        assertTrue(context.getReferences().stream()
                .anyMatch(item -> "semantic.settlement.due-in-month.v1".equals(item.getId())));
        assertTrue(context.getToolHints().contains("project.reconciliation_due"));
        assertTrue(context.getReferences().stream().allMatch(item -> item.getAuthority() != null));
    }

    @Test
    void trustedPackDoesNotContainPromptOrDraftSources() {
        BuiltInAgentKnowledgeRetriever retriever = new BuiltInAgentKnowledgeRetriever(new ObjectMapper());

        assertFalse(retriever.trustedEntries().stream().anyMatch(item ->
                item.getSource().toLowerCase().contains("prompt")
                        || "DRAFT".equalsIgnoreCase(item.getStatus())));
    }
}
