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
    @Test void rejectedResultsDoNotLeakThroughCardsOrSharedTools() {
        AgentV2Models.TaskRequirement a=new AgentV2Models.TaskRequirement(),b=new AgentV2Models.TaskRequirement();
        a.setRequirementId("r1");a.setCapabilityCodes(List.of("project.list"));a.setCriteria(Map.of("managerName","甲"));
        b.setRequirementId("r2");b.setCapabilityCodes(List.of("project.list"));b.setCriteria(Map.of("managerName","乙"));
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();spec.setRequirements(List.of(a,b));
        AgentSkillExecution first=new AgentSkillExecution(),second=new AgentSkillExecution();
        AgentEvidence e1=new AgentEvidence(),e2=new AgentEvidence();
        e1.setEvidenceId("e1");e1.setToolCode("project.list");e1.setCriteria(Map.of("managerName","甲"));
        e2.setEvidenceId("e2");e2.setToolCode("project.list");e2.setCriteria(Map.of("managerName","乙"));
        first.setEvidence(e1);second.setEvidence(e2);second.setCards(List.of(Map.of("type","project-list")));
        assertEquals(List.of(first),AgentRequirementCoverage.acceptedExecutions(spec,List.of(first,second),Set.of("r2")));
        assertTrue(AgentRequirementCoverage.acceptedExecutions(spec,List.of(first,second),Set.of("r1","r2")).isEmpty());
        b.setCriteria(Map.of("managerName","甲"));
        assertEquals(List.of(first),AgentRequirementCoverage.acceptedExecutions(spec,List.of(first,second),Set.of("r1")),"shared evidence may serve an accepted requirement");
    }
    @Test void unrelatedSuccessfulQueryCannotAppearAsPartialAnswer() {
        AgentV2Models.TaskRequirement requirement=new AgentV2Models.TaskRequirement();requirement.setCapabilityCodes(List.of("help.search"));
        AgentV2Models.TaskSpec spec=new AgentV2Models.TaskSpec();spec.setRequirements(List.of(requirement));
        AgentSkillExecution query=new AgentSkillExecution();AgentEvidence e=new AgentEvidence();e.setEvidenceId("e1");e.setToolCode("project.reconciliation_due");query.setEvidence(e);
        assertTrue(AgentRequirementCoverage.acceptedExecutions(spec,List.of(query),Set.of()).isEmpty());
    }
    @Test void datelessCapabilityEvidenceRemainsCompatibleWhenRequirementHasTemporalBounds() {
        AgentV2Models.TaskRequirement requirement = new AgentV2Models.TaskRequirement();
        requirement.setDescription("查询2026年库存与项目列表");
        requirement.setCapabilityCodes(List.of("inventory.materials"));
        requirement.setStartDate("2026-01-01");
        requirement.setEndDate("2026-12-31");
        AgentV2Models.TaskSpec spec = new AgentV2Models.TaskSpec();
        spec.setRequirements(List.of(requirement));

        AgentSkillExecution exec = new AgentSkillExecution();
        AgentEvidence evidence = new AgentEvidence();
        evidence.setToolCode("inventory.materials");
        evidence.setAvailability("AVAILABLE");
        evidence.setCompleteness("COMPLETE");
        evidence.setCriteria(Collections.emptyMap());
        exec.setEvidence(evidence);

        assertTrue(AgentRequirementCoverage.missing(spec, List.of(exec)).isEmpty());
    }

    @Test void multiToolCandidateRequirementAndPhase2And3SnapshotsRemainCompatible() {
        AgentV2Models.TaskRequirement r1 = new AgentV2Models.TaskRequirement();
        r1.setRequirementId("r1");
        r1.setDescription("月度经营复盘与红黄绿灯项目名单");
        r1.setCapabilityCodes(List.of("finance.enterprise_kpi", "risk.owner_action_center"));
        r1.setStartDate(java.time.LocalDate.now().withDayOfMonth(1).toString());
        r1.setEndDate(java.time.YearMonth.now().atEndOfMonth().toString());
        r1.setProjectIds(List.of("p1", "p2", "p3"));
        r1.setCriteria(Map.of("flowType", "租出", "groupBy", "项目"));

        AgentSkillExecution kpiExec = new AgentSkillExecution();
        AgentEvidence kpiEv = new AgentEvidence();
        kpiEv.setEvidenceId("e1");
        kpiEv.setToolCode("finance.enterprise_kpi");
        kpiEv.setAvailability("AVAILABLE");
        kpiEv.setCompleteness("COMPLETE");
        kpiEv.setProjectIds(List.of("p1", "p2", "p3"));
        kpiEv.setTimeRange("asOf=" + java.time.LocalDate.now());
        kpiEv.setCriteria(Map.of("asOfDate", java.time.LocalDate.now().toString()));
        kpiExec.setEvidence(kpiEv);

        AgentV2Models.TaskSpec spec = new AgentV2Models.TaskSpec();
        spec.setRequirements(List.of(r1));
        assertTrue(AgentRequirementCoverage.missing(spec, List.of(kpiExec)).isEmpty());

        AgentV2Models.TaskRequirement r2 = new AgentV2Models.TaskRequirement();
        r2.setRequirementId("r2");
        r2.setDescription("去年租出材料最多的是哪个项目");
        r2.setCapabilityCodes(List.of("material.transaction_aggregate"));
        r2.setStartDate("2025-01-01");
        r2.setEndDate("2025-12-31");
        r2.setCriteria(Map.of("flowType", "租出", "groupBy", "项目", "metric", "数量"));

        AgentSkillExecution matExec = new AgentSkillExecution();
        AgentEvidence matEv = new AgentEvidence();
        matEv.setEvidenceId("e2");
        matEv.setToolCode("material.transaction_aggregate");
        matEv.setAvailability("AVAILABLE");
        matEv.setCompleteness("COMPLETE");
        matEv.setTimeRange("businessDate=2025-01-01~2025-12-31,flowType=RENT_OUT,reviewScope=ALL");
        matEv.setCriteria(Map.of("flowType", "RENT_OUT", "groupBy", "PROJECT", "metric", "QUANTITY"));
        matExec.setEvidence(matEv);

        spec.setRequirements(List.of(r2));
        assertTrue(AgentRequirementCoverage.missing(spec, List.of(matExec)).isEmpty());

        AgentV2Models.TaskRequirement r3 = new AgentV2Models.TaskRequirement();
        r3.setRequirementId("r3");
        r3.setDescription("单项目结算草稿试算");
        r3.setCapabilityCodes(List.of("settlement.preview_draft"));
        r3.setProjectIds(List.of("p1", "p2", "p3"));
        AgentSkillExecution draftExec = new AgentSkillExecution();
        AgentEvidence draftEv = new AgentEvidence();
        draftEv.setEvidenceId("e3");
        draftEv.setToolCode("settlement.preview_draft");
        draftEv.setAvailability("AVAILABLE");
        draftEv.setCompleteness("COMPLETE");
        draftEv.setProjectIds(List.of("p1"));
        draftExec.setEvidence(draftEv);
        spec.setRequirements(List.of(r3));
        assertTrue(AgentRequirementCoverage.missing(spec, List.of(draftExec)).isEmpty());

        AgentV2Models.TaskRequirement r4a = new AgentV2Models.TaskRequirement();
        r4a.setRequirementId("r4a");
        r4a.setDescription("为当前项目做一份出租单建单预览：计划今天起租，出库 500 根 48*3.0 钢管和 1000 个十字扣件");
        r4a.setCapabilityCodes(List.of("document.preview_draft"));
        r4a.setProjectIds(List.of("p1", "p2"));

        AgentV2Models.TaskRequirement r4b = new AgentV2Models.TaskRequirement();
        r4b.setRequirementId("r4b");
        r4b.setDescription("匹配该项目合同单价，试算预计每日租金、装卸杂费，并检查当前基地库存是否充足");
        r4b.setCapabilityCodes(List.of("inventory.materials"));

        AgentSkillExecution docPreviewExec = new AgentSkillExecution();
        AgentEvidence docPreviewEv = new AgentEvidence();
        docPreviewEv.setEvidenceId("e4");
        docPreviewEv.setToolCode("document.preview_draft");
        docPreviewEv.setAvailability("AVAILABLE");
        docPreviewEv.setCompleteness("COMPLETE");
        docPreviewEv.setProjectIds(List.of("p2"));
        docPreviewEv.setCriteria(Map.of("documentType", "RENT_OUT", "workflow", "rentOut"));
        docPreviewExec.setEvidence(docPreviewEv);

        spec.setRequirements(List.of(r4a, r4b));
        assertTrue(AgentRequirementCoverage.missing(spec, List.of(docPreviewExec)).isEmpty());
    }
}
