package com.zjyz.agent.workspace.v2.service;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentRequirementCoverageTest {
    @Test void realProjectQueryEvidenceSatisfiesOwnerAndStatusButNotDifferentOwner() {
        com.zjyz.dao.ProjectMapper mapper=org.mockito.Mockito.mock(com.zjyz.dao.ProjectMapper.class);
        org.mockito.Mockito.when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(Collections.emptyList());
        com.zjyz.agent.workspace.model.AgentRuntimeRecords.Workspace workspace=new com.zjyz.agent.workspace.model.AgentRuntimeRecords.Workspace();
        workspace.setCid("tenant");workspace.setSelectionMode("ALL");
        AgentSkillExecution result=new com.zjyz.agent.workspace.tool.ProjectCatalogSkill(mapper,new com.fasterxml.jackson.databind.ObjectMapper())
            .execute("{\"managerName\":\"张经理\",\"status\":\"ONGOING\"}","项目清单",workspace);
        AgentV2Models.TaskRequirement requirement=new AgentV2Models.TaskRequirement();
        requirement.setDescription("负责人项目");requirement.setCapabilityCodes(List.of("project.list"));
        requirement.setRequireComplete(true);requirement.setCriteria(Map.of("managerName","张经理","status","ONGOING"));
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();spec.setRequirements(List.of(requirement));
        assertTrue(AgentRequirementCoverage.missing(spec,List.of(result)).isEmpty());
        requirement.setCriteria(Map.of("managerName","李经理"));
        assertFalse(AgentRequirementCoverage.missing(spec,List.of(result)).isEmpty());
    }
    @Test void laterCompleteObservationIsNotHiddenByEarlierPartialEvidence() {
        AgentV2Models.TaskRequirement requirement=new AgentV2Models.TaskRequirement();requirement.setCapabilityCodes(List.of("project.list"));requirement.setRequireComplete(true);
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();spec.setRequirements(List.of(requirement));
        AgentSkillExecution partial=new AgentSkillExecution(), complete=new AgentSkillExecution();
        AgentEvidence first=new AgentEvidence(), last=new AgentEvidence();first.setToolCode("project.list");last.setToolCode("project.list");
        first.setCompleteness("PARTIAL");last.setCompleteness("COMPLETE");partial.setEvidence(first);complete.setEvidence(last);
        assertTrue(AgentRequirementCoverage.missing(spec,List.of(partial,complete)).isEmpty());
    }
    @Test void successfulInventoryQueryCannotCompleteMarketStep() {
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();
        AgentV2Models.TaskRequirement stock=new AgentV2Models.TaskRequirement();stock.setDescription("查库存");stock.setCapabilityCodes(List.of("inventory.materials"));
        AgentV2Models.TaskRequirement market=new AgentV2Models.TaskRequirement();market.setDescription("去杭州商城找缺少的钢管");market.setCapabilityCodes(List.of("market.search"));
        spec.setRequirements(List.of(stock,market));
        AgentSkillExecution result=new AgentSkillExecution();AgentEvidence evidence=new AgentEvidence();
        evidence.setToolCode("inventory.materials");result.setEvidence(evidence);
        assertEquals(List.of("去杭州商城找缺少的钢管"),AgentRequirementCoverage.missing(spec,List.of(result)));
        AgentSkillExecution second=new AgentSkillExecution();AgentEvidence other=new AgentEvidence();other.setToolCode("market.search");second.setEvidence(other);
        assertTrue(AgentRequirementCoverage.missing(spec,List.of(result,second)).isEmpty());
        second.setNeedClarification(true);
        assertFalse(AgentRequirementCoverage.missing(spec,List.of(result,second)).isEmpty());
    }
    @Test void unsupportedRequirementRemainsMissing() {
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();AgentV2Models.TaskRequirement unsupported=new AgentV2Models.TaskRequirement();
        unsupported.setDescription("自动采购");spec.setRequirements(List.of(unsupported));
        assertEquals(List.of("自动采购"),AgentRequirementCoverage.missing(spec,Collections.emptyList()));
    }
    @Test void sameToolForWrongCityCannotSatisfyAnotherRequirement() {
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();
        AgentV2Models.TaskRequirement requirement=new AgentV2Models.TaskRequirement();
        requirement.setDescription("杭州钢管");requirement.setCapabilityCodes(List.of("market.search"));
        requirement.setCriteria(Map.of("city","杭州"));spec.setRequirements(List.of(requirement));
        AgentSkillExecution result=new AgentSkillExecution();AgentEvidence evidence=new AgentEvidence();
        evidence.setToolCode("market.search");evidence.setCriteria(Map.of("city","上海"));result.setEvidence(evidence);
        assertEquals(List.of("杭州钢管"),AgentRequirementCoverage.missing(spec,List.of(result)));
        evidence.setCriteria(Map.of("city","杭州"));assertTrue(AgentRequirementCoverage.missing(spec,List.of(result)).isEmpty());
        spec.setRequirements(List.of(requirement,requirement));
        assertTrue(AgentRequirementCoverage.missing(spec,List.of(result,result)).isEmpty(), "one evidence can support multiple compatible requirements");
    }

    @Test void partialDatasetCannotCompleteWholeQuestion() {
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();
        AgentV2Models.TaskRequirement requirement=new AgentV2Models.TaskRequirement();
        requirement.setDescription("全企业材料排名");requirement.setCapabilityCodes(List.of("finance.material_settled_rent"));spec.setRequirements(List.of(requirement));
        AgentSkillExecution result=new AgentSkillExecution();AgentEvidence evidence=new AgentEvidence();
        evidence.setToolCode("finance.material_settled_rent");evidence.setCriteria(Map.of("completeness","PARTIAL"));result.setEvidence(evidence);
        assertFalse(AgentRequirementCoverage.missing(spec,List.of(result)).isEmpty());
        evidence.setCriteria(Map.of("availability","FORBIDDEN"));assertFalse(AgentRequirementCoverage.missing(spec,List.of(result)).isEmpty());
        evidence.setCriteria(Map.of("availability","AVAILABLE","completeness","COMPLETE"));assertTrue(AgentRequirementCoverage.missing(spec,List.of(result)).isEmpty());
    }
    @Test void criteriaBasedMetricTimeAndCompletenessMatchTypedRequirement() {
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();
        AgentV2Models.TaskRequirement r=new AgentV2Models.TaskRequirement();
        r.setDescription("结算租金"); r.setCapabilityCodes(List.of("finance.material_settled_rent"));
        r.setMetricId("settled_rent"); r.setTimeBasis("SETTLEMENT_END_DATE"); r.setRequireComplete(true);
        spec.setRequirements(List.of(r));
        AgentSkillExecution result=new AgentSkillExecution(); AgentEvidence e=new AgentEvidence();
        e.setToolCode("finance.material_settled_rent"); result.setEvidence(e);
        e.setCriteria(Map.of("metricId","settled_rent","dateBasis","SETTLEMENT_END_DATE","completeness","COMPLETE"));
        assertTrue(AgentRequirementCoverage.missing(spec,List.of(result)).isEmpty());
        e.setCriteria(Map.of("metricId","cash_receipts","dateBasis","SETTLEMENT_END_DATE","completeness","COMPLETE"));
        assertFalse(AgentRequirementCoverage.missing(spec,List.of(result)).isEmpty());
    }
    @Test void oneYearCannotSatisfyAnotherYearEvenWithLegacyEmptyCriteria() {
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();
        AgentV2Models.TaskRequirement oldYear=new AgentV2Models.TaskRequirement();
        oldYear.setDescription("统计2025年已结算材料租金");oldYear.setCapabilityCodes(List.of("finance.material_settled_rent"));
        AgentV2Models.TaskRequirement newYear=new AgentV2Models.TaskRequirement();
        newYear.setDescription("统计2026年已结算材料租金");newYear.setCapabilityCodes(List.of("finance.material_settled_rent"));
        spec.setRequirements(List.of(oldYear,newYear));
        AgentSkillExecution x=new AgentSkillExecution();AgentEvidence e=new AgentEvidence();x.setEvidence(e);
        e.setToolCode("finance.material_settled_rent");e.setAvailability("AVAILABLE");e.setCompleteness("COMPLETE");
        e.setCriteria(Map.of("startDate","2026-01-01","endDate","2026-12-31"));
        assertEquals(List.of(oldYear.getDescription()),AgentRequirementCoverage.missing(spec,List.of(x)));
    }
    @Test void exactProjectAndDateBoundsAreRequiredAndPartialPageCannotCompleteRequirement() {
        AgentV2Models.TaskRequirement r=new AgentV2Models.TaskRequirement();r.setDescription("指定期间项目账目");
        r.setCapabilityCodes(List.of("query"));r.setStartDate("2026-09-01");r.setEndDate("2026-09-30");r.setProjectIds(List.of("p1"));r.setRequireComplete(true);
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();spec.setRequirements(List.of(r));
        AgentSkillExecution x=new AgentSkillExecution();AgentEvidence e=new AgentEvidence();x.setEvidence(e);e.setToolCode("query");
        e.setCompleteness("COMPLETE");e.setProjectIds(List.of("p2"));e.setCriteria(Map.of("startDate","2026-09-01","endDate","2026-09-30"));
        assertFalse(AgentRequirementCoverage.missing(spec,List.of(x)).isEmpty());
        e.setProjectIds(List.of("p1"));assertTrue(AgentRequirementCoverage.missing(spec,List.of(x)).isEmpty());
        e.setCriteria(Map.of("startDate","2026-09-01","endDate","2026-09-15"));assertFalse(AgentRequirementCoverage.missing(spec,List.of(x)).isEmpty());
        e.setCriteria(Map.of("startDate","2026-09-01","endDate","2026-09-30"));e.setCompleteness("PARTIAL");assertFalse(AgentRequirementCoverage.missing(spec,List.of(x)).isEmpty());
    }
}
