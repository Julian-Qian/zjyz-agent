package com.zjyz.agent.workspace.tool;
import com.fasterxml.jackson.databind.ObjectMapper;import com.zjyz.dao.*;import com.zjyz.pojo.entity.*;import com.zjyz.agent.orch.AgentSkillExecution;import com.zjyz.agent.workspace.model.AgentRuntimeRecords;import org.junit.jupiter.api.Test;import java.time.*;import java.math.BigDecimal;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.any;
class AgentDeterministicAnalyticsTest {
 private final ProjectMapper projects=mock(ProjectMapper.class);private final ContractMapper contracts=mock(ContractMapper.class);private final SettlementDocumentMapper docs=mock(SettlementDocumentMapper.class);private final SettlementRentFeeSegmentMapper segments=mock(SettlementRentFeeSegmentMapper.class);private final RentDocumentMapper rents=mock(RentDocumentMapper.class);private final ReturnDocumentMapper returns=mock(ReturnDocumentMapper.class);private final RentInReturnDocumentMapper rentInReturns=mock(RentInReturnDocumentMapper.class);
 private AgentDeterministicAnalytics service(){ProjectEntity p=new ProjectEntity();p.setCid("c");p.setProjectId("p");p.setProjectBusinessType("rent_out");p.setProjectStatusFlag("0");when(projects.selectList(any())).thenReturn(Collections.singletonList(p));return new AgentDeterministicAnalytics(projects,contracts,docs,segments,rents,mock(RentInDocumentMapper.class),returns,rentInReturns,new ObjectMapper());}
 private AgentRuntimeRecords.Workspace workspace(){AgentRuntimeRecords.Workspace w=new AgentRuntimeRecords.Workspace();w.setCid("c");w.setFinanceEnabled(true);w.setSelectionMode("EXPLICIT");w.setProjectIds(Collections.singletonList("p"));return w;}
 @Test void intervalCoverageDoesNotConfuseLatestEndWithCompleteCoverage(){SettlementDocumentEntity a=new SettlementDocumentEntity();a.setStartDate("2026-01-01");a.setEndDate("2026-01-15");SettlementDocumentEntity b=new SettlementDocumentEntity();b.setStartDate("2026-01-17");b.setEndDate("2026-01-31");assertFalse(AgentDeterministicAnalytics.covered(LocalDate.of(2026,1,1),LocalDate.of(2026,1,31),Arrays.asList(a,b)));b.setStartDate("2026-01-16");assertTrue(AgentDeterministicAnalytics.covered(LocalDate.of(2026,1,1),LocalDate.of(2026,1,31),Arrays.asList(a,b)));}
 @Test void missingSegmentsRefuseRanking(){AgentDeterministicAnalytics s=service();SettlementDocumentEntity d=document();when(docs.selectList(any())).thenReturn(Collections.singletonList(d));AgentSkillExecution out=s.execute("finance.material_settled_rent","{}",workspace());assertEquals("MISSING_DATA",out.getEvidence().getCriteria().get("availability"));assertEquals(false,out.getEvidence().getCriteria().get("globalRankingAllowed"));assertTrue(((List<?>)out.getCards().get(0).get("items")).isEmpty());}
 @Test void verifiedRentRanksCurrencyNotMaterialUnits(){AgentDeterministicAnalytics s=service();SettlementDocumentEntity d=document();when(docs.selectList(any())).thenReturn(Collections.singletonList(d));SettlementRentFeeSegmentEntity f=new SettlementRentFeeSegmentEntity();f.setCid("c");f.setProjectId("p");f.setSettlementDocumentId("s");f.setMaterialId("m");f.setIncrementAmount(BigDecimal.ZERO);f.setMaterialFee(new BigDecimal("100.00"));when(segments.selectList(any())).thenReturn(Collections.singletonList(f));AgentSkillExecution out=s.execute("finance.material_settled_rent","{}",workspace());assertEquals(true,out.getEvidence().getCriteria().get("globalRankingAllowed"));assertEquals(1,((List<?>)out.getCards().get(0).get("items")).size());}
 @Test void quarterlyContractDoesNotBecomeMonthlyDue(){AgentDeterministicAnalytics s=service();ContractEntity c=new ContractEntity();c.setReconciliationPeriod(3);when(contracts.selectList(any())).thenReturn(Collections.singletonList(c));LocalDate now=LocalDate.now(ZoneId.of("Asia/Shanghai"));RentDocumentEntity d=new RentDocumentEntity();d.setRentDate(now.withDayOfMonth(1).toString());when(rents.selectList(any())).thenReturn(Collections.singletonList(d));AgentSkillExecution out=s.execute("project.reconciliation_schedule","{}",workspace());assertEquals(0,out.getCards().get(0).get("totalCount"));}
 @Test void scheduleMissingContractIsNotAnEmptySuccessfulPlan(){
  AgentDeterministicAnalytics s=service();
  AgentSkillExecution out=s.execute("project.reconciliation_schedule","{}",workspace());
  assertEquals("MISSING_DATA",out.getEvidence().getCriteria().get("availability"));
  assertEquals("PARTIAL",out.getEvidence().getCriteria().get("completeness"));
  assertEquals(1,out.getEvidence().getCriteria().get("ruleMissingProjects"));
 }
 @Test void materialScheduleCannotMasqueradeAsFinanceSchedule(){
  AgentSkillExecution out=service().execute("project.reconciliation_schedule","{\"reconciliationType\":\"MATERIAL\"}",workspace());
  assertEquals("UNSUPPORTED",out.getEvidence().getCriteria().get("availability"));
 }
 @Test void financePermissionIsRequiredBeforeReadingSettlements(){
  AgentRuntimeRecords.Workspace w=workspace();w.setFinanceEnabled(false);
  assertThrows(com.zjyz.common.exception.MyBizException.class,()->service().execute("finance.material_settled_rent","{}",w));
  verify(docs,never()).selectList(any());
 }
 @Test void partialMoneyCannotBePresentedAsGlobalRanking(){
  AgentDeterministicAnalytics s=service();SettlementDocumentEntity a=document();SettlementDocumentEntity b=document();b.setSettlementDocumentId("missing");b.setStartDate("2026-02-01");b.setEndDate("2026-02-28");
  when(docs.selectList(any())).thenReturn(Arrays.asList(a,b));
  SettlementRentFeeSegmentEntity f=new SettlementRentFeeSegmentEntity();f.setCid("c");f.setProjectId("p");f.setSettlementDocumentId("s");f.setMaterialId("m");f.setIncrementAmount(BigDecimal.ZERO);f.setMaterialFee(new BigDecimal("100.00"));
  when(segments.selectList(any())).thenReturn(Collections.singletonList(f),Collections.emptyList());
  AgentSkillExecution out=s.execute("finance.material_settled_rent","{}",workspace());
  assertEquals(1,out.getEvidence().getCriteria().get("coverageNumerator"));assertEquals(2,out.getEvidence().getCriteria().get("coverageDenominator"));
  assertEquals(false,out.getEvidence().getCriteria().get("globalRankingAllowed"));assertTrue(((List<?>)out.getCards().get(0).get("items")).isEmpty());
 }
 @Test void mismatchedRentSnapshotCannotBeReportedAsVerified(){
  AgentDeterministicAnalytics s=service();when(docs.selectList(any())).thenReturn(Collections.singletonList(document()));
  SettlementRentFeeSegmentEntity f=new SettlementRentFeeSegmentEntity();f.setCid("c");f.setProjectId("p");f.setSettlementDocumentId("s");f.setMaterialId("m");f.setIncrementAmount(BigDecimal.ZERO);f.setMaterialFee(new BigDecimal("99.99"));when(segments.selectList(any())).thenReturn(Collections.singletonList(f));
  AgentSkillExecution out=s.execute("finance.material_settled_rent","{}",workspace());
  assertEquals("MISSING_DATA",out.getEvidence().getCriteria().get("availability"));assertEquals(false,out.getEvidence().getCriteria().get("globalRankingAllowed"));
 }
 @Test void incrementRentChangesTheRankingAndBothSummariesAreVerified(){
  AgentDeterministicAnalytics s=service();SettlementDocumentEntity d=document();
  d.setSummarySnapshotJson("{\"summary\":{\"currentRentFee\":250,\"incrementRentFee\":100}}");
  when(docs.selectList(any())).thenReturn(List.of(d));
  SettlementRentFeeSegmentEntity a=segment("a","100","100"),b=segment("b","150","0");
  when(segments.selectList(any())).thenReturn(List.of(a,b));
  AgentSkillExecution out=s.execute("finance.material_settled_rent","{}",workspace());
  List<?> rows=(List<?>)out.getCards().get(0).get("items");
  assertEquals("a",((Map<?,?>)rows.get(0)).get("materialId"));
  assertEquals(new BigDecimal("200.00"),((Map<?,?>)rows.get(0)).get("settledRentAmount"));
  assertEquals(true,out.getEvidence().getCriteria().get("includesIncrementRent"));
  assertEquals("COMPLETE",out.getEvidence().getCompleteness());
  a.setIncrementAmount(new BigDecimal("99"));
  AgentSkillExecution wrong=s.execute("finance.material_settled_rent","{}",workspace());
  assertEquals(false,wrong.getEvidence().getCriteria().get("globalRankingAllowed"));
 }
 @Test void missingIncrementSnapshotNeverDefaultsToZero(){
  AgentDeterministicAnalytics s=service();SettlementDocumentEntity d=document();d.setSummarySnapshotJson("{\"summary\":{\"currentRentFee\":100}}");
  when(docs.selectList(any())).thenReturn(List.of(d));when(segments.selectList(any())).thenReturn(List.of(segment("a","100","0")));
  assertEquals("MISSING_DATA",s.execute("finance.material_settled_rent","{}",workspace()).getEvidence().getAvailability());
 }
 @Test void completedAndSettledProjectDoesNotGeneratePostClosureObligations(){
  AgentDeterministicAnalytics s=service();prepareClosedProject();
  SettlementDocumentEntity settled=document();settled.setStartDate("2026-01-01");settled.setEndDate("2026-02-15");
  when(docs.selectList(any())).thenReturn(List.of(settled));
  AgentSkillExecution out=s.execute("project.reconciliation_schedule","{}",workspace());
  assertEquals(0,out.getCards().get(0).get("totalCount"));assertEquals("AVAILABLE",out.getEvidence().getAvailability());
 }
 @Test void completedProjectFinalPartialPeriodStopsAtReturnDate(){
  AgentDeterministicAnalytics s=service();prepareClosedProject();
  AgentSkillExecution out=s.execute("project.reconciliation_schedule","{}",workspace());
  List<?> rows=(List<?>)out.getCards().get(0).get("items");assertEquals(2,rows.size());
  assertEquals("2026-02-15",((Map<?,?>)rows.get(1)).get("periodEnd"));
  assertEquals("LAST_RETURN_DATE_COMPLETED_PROJECT",((Map<?,?>)rows.get(1)).get("closingBasis"));
 }
 @Test void completedProjectWithoutClosingEvidenceIsMissingRule(){
  AgentDeterministicAnalytics s=service();prepareClosedProject();when(returns.selectList(any())).thenReturn(Collections.emptyList());
  AgentSkillExecution out=s.execute("project.reconciliation_schedule","{}",workspace());
  assertEquals("MISSING_DATA",out.getEvidence().getAvailability());
  assertEquals("RULE_MISSING",((Map<?,?>)((List<?>)out.getCards().get(0).get("items")).get(0)).get("status"));
 }
 @Test void newRentAfterClosingReturnDoesNotCreateFalseFinalSchedule(){
  AgentDeterministicAnalytics s=service();prepareClosedProject();RentDocumentEntity newer=new RentDocumentEntity();newer.setRentDate("2026-03-01");
  RentDocumentEntity first=new RentDocumentEntity();first.setRentDate("2026-01-01");when(rents.selectList(any())).thenReturn(List.of(first,newer));
  assertEquals("MISSING_DATA",s.execute("project.reconciliation_schedule","{}",workspace()).getEvidence().getAvailability());
 }
 @Test void ongoingRenewalIsNotStoppedMerelyByAnOldContractEndDate(){
  AgentDeterministicAnalytics s=service();ContractEntity c=new ContractEntity();c.setReconciliationPeriod(1);c.setEndDate("2026-02-15");
  when(contracts.selectList(any())).thenReturn(List.of(c));RentDocumentEntity first=new RentDocumentEntity();first.setRentDate("2026-01-01");when(rents.selectList(any())).thenReturn(List.of(first));
  AgentSkillExecution out=s.execute("project.reconciliation_schedule","{}",workspace());
  List<?> rows=(List<?>)out.getCards().get(0).get("items");assertTrue(rows.size()>2);
  verify(returns,never()).selectList(any());
 }
 private void prepareClosedProject(){
  ProjectEntity p=new ProjectEntity();p.setCid("c");p.setProjectId("p");p.setProjectBusinessType("rent_out");p.setProjectStatusFlag("1");when(projects.selectList(any())).thenReturn(List.of(p));
  ContractEntity c=new ContractEntity();c.setReconciliationPeriod(1);when(contracts.selectList(any())).thenReturn(List.of(c));
  RentDocumentEntity first=new RentDocumentEntity();first.setRentDate("2026-01-01");when(rents.selectList(any())).thenReturn(List.of(first));
  ReturnDocumentEntity last=new ReturnDocumentEntity();last.setReturnDate("2026-02-15");when(returns.selectList(any())).thenReturn(List.of(last));
 }
 private SettlementRentFeeSegmentEntity segment(String id,String base,String increment){
  SettlementRentFeeSegmentEntity f=new SettlementRentFeeSegmentEntity();f.setCid("c");f.setProjectId("p");f.setSettlementDocumentId("s");f.setMaterialId(id);f.setMaterialFee(new BigDecimal(base));f.setIncrementAmount(new BigDecimal(increment));return f;
 }
 private SettlementDocumentEntity document(){SettlementDocumentEntity d=new SettlementDocumentEntity();d.setCid("c");d.setProjectId("p");d.setSettlementDocumentId("s");d.setStartDate("2026-01-01");d.setEndDate("2026-01-31");d.setStatus("ACTIVE");d.setSummarySnapshotJson("{\"summary\":{\"currentRentFee\":100.00,\"incrementRentFee\":0.00}}");return d;}
}
