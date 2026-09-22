package com.zjyz.agent.workspace.v2.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
class AgentRequirementScopeCompatibilityTest {
 @Test void relatedTurnValidatesInheritedScopeWithoutGrantingUnionOfCandidateAndPrevious() {
  AgentV2ConversationInterpreter interpreter=new AgentV2ConversationInterpreter(mock(AgentV2ModelGateway.class),new ObjectMapper());
  AgentV2Models.ScopeSnapshot scope=new AgentV2Models.ScopeSnapshot();scope.setProjectIds(List.of("candidate"));
  scope.setContextTaskId("previous");scope.setInheritedProjectIds(List.of("inherited"));scope.setExplicitOverride(false);
  AgentV2Models.TaskRequirement r=new AgentV2Models.TaskRequirement();r.setProjectIds(List.of("inherited"));
  assertDoesNotThrow(()->interpreter.validateRequirementScope(r,scope,"CONTINUE"));
  assertThrows(IllegalArgumentException.class,()->interpreter.validateRequirementScope(r,scope,"NEW"));
  scope.setExplicitOverride(true);
  assertThrows(IllegalArgumentException.class,()->interpreter.validateRequirementScope(r,scope,"CONTINUE"));
 }
 @Test void monthInLegacyDescriptionMustNotBecomeFullYear() {
  AgentV2Models.TaskRequirement r=new AgentV2Models.TaskRequirement();r.setDescription("2025年9月财务对账");r.setCapabilityCodes(List.of("project.reconciliation_schedule"));
  AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();spec.setRequirements(List.of(r));
  AgentEvidence e=new AgentEvidence();e.setToolCode("project.reconciliation_schedule");e.setCriteria(Map.of("targetMonth","2025-09"));
  AgentSkillExecution execution=new AgentSkillExecution();execution.setEvidence(e);
  assertTrue(AgentRequirementCoverage.missing(spec,List.of(execution)).isEmpty());
  e.setCriteria(Map.of("targetMonth","2025-10"));assertFalse(AgentRequirementCoverage.missing(spec,List.of(execution)).isEmpty());
 }
}
