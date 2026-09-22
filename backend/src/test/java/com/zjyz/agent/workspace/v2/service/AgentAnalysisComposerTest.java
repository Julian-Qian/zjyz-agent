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
        assertFalse(composer.validClaims(json.readTree("[{\"type\":\"INFERENCE\",\"text\":\"利润很好\",\"evidenceIds\":[]}]"),Map.of("e1","现金登记")));
    }
}
