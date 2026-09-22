package com.zjyz.agent.workspace.v2.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentRequirementVerifierTest {
 private final ObjectMapper json=new ObjectMapper();
 private final AgentRequirementVerifier verifier=new AgentRequirementVerifier(mock(AgentV2ModelGateway.class),json);
 private List<Map<String,Object>> rows(String status){return List.of(Map.of("id","r1","status",status,"evidenceIds",List.of("e1")));}
 @Test void acceptsSingleJsonFenceButRejectsTrailingPayload() throws Exception {
  assertTrue(verifier.parseResponse("```json\n{\"allRequirementsCaptured\":true}\n```").path("allRequirementsCaptured").asBoolean());
  assertThrows(Exception.class,()->verifier.parseResponse("{} {}"));
 }
 @Test void verifierMayRejectButNeverPromoteMissingDeterministicEvidence()throws Exception {
  String answer="{\"allRequirementsCaptured\":true,\"requirements\":[{\"id\":\"r1\",\"covered\":true,\"evidenceIds\":[\"e1\"]}]}";
  assertTrue(verifier.validate(json.readTree(answer),rows("UNSATISFIED")).rejected.contains("r1"));
  assertTrue(verifier.validate(json.readTree(answer),rows("SATISFIED")).rejected.isEmpty());
 }
 @Test void omittedUserRequirementAndForeignCitationFailClosed()throws Exception {
  String answer="{\"allRequirementsCaptured\":false,\"requirements\":[{\"id\":\"r1\",\"covered\":true,\"evidenceIds\":[\"e1\"]}]}";
  assertFalse(verifier.validate(json.readTree(answer),rows("SATISFIED")).checked);
  answer=answer.replace("Captured\":false","Captured\":true").replace("e1","invented");
  assertTrue(verifier.validate(json.readTree(answer),rows("SATISFIED")).rejected.contains("r1"));
 }
 @Test void semanticTimeMismatchCanRejectStructurallyCompatibleEvidence()throws Exception {
  String answer="{\"allRequirementsCaptured\":true,\"requirements\":[{\"id\":\"r1\",\"covered\":false,\"evidenceIds\":[]}]}";
  AgentRequirementVerifier.Result result=verifier.validate(json.readTree(answer),rows("SATISFIED"));
  assertTrue(result.checked);assertEquals(Set.of("r1"),result.rejected);
 }
}
