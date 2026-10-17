package com.zjyz.agent.workspace.v2.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentAnalysisComposerTest {
    private final ObjectMapper json=new ObjectMapper();
    private final AgentAnalysisComposer composer=new AgentAnalysisComposer(mock(AgentV2ModelGateway.class),json);
    @Test void rejectsInventedReferenceAndAmount() throws Exception {
        assertFalse(composer.validClaims(json.readTree("[{\"type\":\"FACT\",\"text\":\"未归还100根\",\"evidenceIds\":[\"fake\"]}]"),Map.of("e1","未归还100根")));
        assertFalse(composer.validClaims(json.readTree("[{\"type\":\"FACT\",\"text\":\"收入200元\",\"evidenceIds\":[\"e1\"]}]"),Map.of("e1","收入100元")));
    }
    @Test void acceptsGroundedClaimsAndRejectsUncitedProse() throws Exception {
        assertTrue(composer.validClaims(json.readTree("[{\"type\":\"FACT\",\"text\":\"未归还100根\",\"evidenceIds\":[\"e1\"]}]"),Map.of("e1","未归还100根")));
        assertTrue(composer.validClaims(json.readTree("[{\"type\":\"RECOMMENDATION\",\"text\":\"1. 优先催收12500元欠款\",\"evidenceIds\":[\"e1\"]}]"),Map.of("e1","应收12,500.00元")));
        assertFalse(composer.validClaims(json.readTree("[{\"type\":\"INFERENCE\",\"text\":\"利润很好\",\"evidenceIds\":[]}]"),Map.of("e1","现金登记")));
    }
    @Test void groupedAnalysisLeadsWithFindingAndPreservesCitationsAndLimitations() {
        AgentV2ModelGateway gateway=mock(AgentV2ModelGateway.class);
        com.zjyz.agent.workspace.modelgateway.AgentModelGateway.ModelResult draft=new com.zjyz.agent.workspace.modelgateway.AgentModelGateway.ModelResult();
        draft.setSuccess(true);draft.setContent("{\"claims\":[{\"type\":\"FACT\",\"text\":\"未归还100根\",\"evidenceIds\":[\"e1\"]},{\"type\":\"INFERENCE\",\"text\":\"仍有材料待归还\",\"evidenceIds\":[\"e1\"]},{\"type\":\"RECOMMENDATION\",\"text\":\"建议核对归还计划\",\"evidenceIds\":[\"e1\"]},{\"type\":\"LIMITATION\",\"text\":\"无法确定实际归还日期\",\"evidenceIds\":[\"e1\"]}]}");
        com.zjyz.agent.workspace.modelgateway.AgentModelGateway.ModelResult review=new com.zjyz.agent.workspace.modelgateway.AgentModelGateway.ModelResult();review.setSuccess(true);review.setContent("{\"supported\":true}");
        when(gateway.isAvailable()).thenReturn(true);when(gateway.complete(anyList(),anyList(),anyString())).thenReturn(draft,review);
        com.zjyz.agent.orch.AgentSkillExecution execution=new com.zjyz.agent.orch.AgentSkillExecution();execution.setAnswer("未归还100根，缺少实际归还日期。");
        execution.setCards(java.util.List.of());execution.setWarnings(java.util.List.of());execution.setEvidence(new com.zjyz.agent.model.AgentEvidence());
        AgentAnalysisComposer.Result result=new AgentAnalysisComposer(gateway,json).compose("分析归还情况",java.util.List.of(execution),()->{},r->{});
        assertTrue(result.success);assertTrue(result.answer.startsWith("**分析**"));
        assertTrue(result.answer.indexOf("**事实**")<result.answer.indexOf("**建议**"));
        assertTrue(result.answer.contains("100根"));assertTrue(result.answer.contains("无法确定实际归还日期"));assertEquals(4,result.claims.size());
        assertEquals(java.util.List.of("e1"),result.claims.get(0).get("evidenceIds"));
    }

}
