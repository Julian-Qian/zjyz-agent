package com.zjyz.agent.workspace.risk;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotService;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.ProjectAction;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.Query;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.Result;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.RiskFact;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.CompensationDocumentMapper;
import com.zjyz.dao.ContractMapper;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.dao.ReconciliationDocumentMapper;
import com.zjyz.dao.RentDocumentMapper;
import com.zjyz.dao.RentDocumentMaterialMapper;
import com.zjyz.dao.RentInDocumentMapper;
import com.zjyz.dao.RentInDocumentMaterialMapper;
import com.zjyz.dao.RentInReturnDocumentMapper;
import com.zjyz.dao.RentInReturnDocumentMaterialMapper;
import com.zjyz.dao.ReturnDocumentMapper;
import com.zjyz.dao.ReturnDocumentMaterialMapper;
import com.zjyz.dao.SettlementDocumentMapper;
import com.zjyz.pojo.entity.ContractEntity;
import com.zjyz.pojo.entity.ProjectEntity;
import com.zjyz.pojo.entity.RentDocumentEntity;
import com.zjyz.pojo.entity.RentDocumentMaterialEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerActionCenterServiceImplTest {
    private final ProjectMapper projectMapper = mock(ProjectMapper.class);
    private final ContractMapper contractMapper = mock(ContractMapper.class);
    private final SettlementDocumentMapper settlementMapper = mock(SettlementDocumentMapper.class);
    private final ReconciliationDocumentMapper reconciliationMapper = mock(ReconciliationDocumentMapper.class);
    private final RentDocumentMapper rentMapper = mock(RentDocumentMapper.class);
    private final ReturnDocumentMapper returnMapper = mock(ReturnDocumentMapper.class);
    private final CompensationDocumentMapper compensationMapper = mock(CompensationDocumentMapper.class);
    private final RentInDocumentMapper rentInMapper = mock(RentInDocumentMapper.class);
    private final RentInReturnDocumentMapper rentInReturnMapper = mock(RentInReturnDocumentMapper.class);
    private final RentDocumentMaterialMapper rentMaterialMapper = mock(RentDocumentMaterialMapper.class);
    private final ReturnDocumentMaterialMapper returnMaterialMapper = mock(ReturnDocumentMaterialMapper.class);
    private final RentInDocumentMaterialMapper rentInMaterialMapper = mock(RentInDocumentMaterialMapper.class);
    private final RentInReturnDocumentMaterialMapper rentInReturnMaterialMapper = mock(RentInReturnDocumentMaterialMapper.class);
    private final FinanceLedgerSnapshotService financeService = mock(FinanceLedgerSnapshotService.class);
    private OwnerActionCenterServiceImpl service;

    @BeforeEach
    void setUp() {
        when(contractMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(settlementMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(reconciliationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(returnMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(compensationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentInMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentInReturnMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentMaterialMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(returnMaterialMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentInMaterialMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(rentInReturnMaterialMapper.selectList(any())).thenReturn(Collections.emptyList());
        service = new OwnerActionCenterServiceImpl(projectMapper, contractMapper, settlementMapper,
                reconciliationMapper, rentMapper, returnMapper, compensationMapper, rentInMapper,
                rentInReturnMapper, rentMaterialMapper, returnMaterialMapper, rentInMaterialMapper,
                rentInReturnMaterialMapper, financeService,
                Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void contractDimensionDistinguishesExpiredExpiringMissingEndMissingContractAndInvalidDate() {
        when(projectMapper.selectList(any())).thenReturn(Arrays.asList(
                project("P1", "逾期", "0"), project("P2", "临期", "0"), project("P3", "无截止", "0"),
                project("P4", "无合同", "0"), project("P5", "坏日期", "0")));
        when(contractMapper.selectList(any())).thenReturn(Arrays.asList(
                contract("P1", "C1", "2026-07-01"), contract("P2", "C2", "2026-09-15"),
                contract("P3", "C3", null), contract("P5", "C5", "2026-88-99")));

        Result result = service.generate(all(true), query("CONTRACT_EXPIRED"));

        Map<String, String> statuses = result.getItems().stream().collect(Collectors.toMap(
                ProjectAction::getProjectId, item -> String.valueOf(item.getRisks().get(0).getRawMetrics().get("contractStatus"))));
        assertEquals("EXPIRED", statuses.get("P1"));
        assertEquals("EXPIRING_SOON", statuses.get("P2"));
        assertEquals("NO_END_DATE", statuses.get("P3"));
        assertEquals("NO_CONTRACT", statuses.get("P4"));
        assertEquals("INVALID_END_DATE", statuses.get("P5"));
    }

    @Test
    void completedProjectNaturalContractExpiryDoesNotCreateAction() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "已完成", "1")));
        when(contractMapper.selectList(any())).thenReturn(Collections.singletonList(contract("P1", "C1", "2026-01-01")));
        Query query = query("CONTRACT_EXPIRED");
        query.setProjectStatus("COMPLETED");

        Result result = service.generate(all(true), query);

        assertEquals(0, result.getProjectTotalCount());
    }

    @Test
    void materialOutstandingUsesReviewedDocumentsAndKeepsRootTonPieceSeparate() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "材料项目", "0")));
        RentDocumentEntity reviewed = rent("P1", "D1", 1, "2026-08-30");
        RentDocumentEntity draft = rent("P1", "D2", 0, "2026-08-30");
        RentDocumentEntity future = rent("P1", "D3", 1, "2026-09-01");
        when(rentMapper.selectList(any())).thenReturn(Arrays.asList(reviewed, draft, future));
        when(rentMaterialMapper.selectList(any())).thenReturn(Arrays.asList(
                material("P1", "D1", "M1", "根", 10), material("P1", "D1", "M2", "吨", 2),
                material("P1", "D1", "M3", "件", 7), material("P1", "D2", "M4", "件", 99),
                material("P1", "D3", "M5", "件", 88)));

        Result result = service.generate(all(false), query("MATERIAL_OUTSTANDING"));

        RiskFact risk = result.getItems().get(0).getRisks().get(0);
        assertEquals(3, risk.getRawMetrics().get("outstandingMaterialKinds"));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) risk.getRawMetrics().get("unitGroups");
        assertEquals(Arrays.asList("件", "吨", "根"), groups.stream().map(item -> item.get("unit")).collect(Collectors.toList()));
        assertNull(risk.getRawMetrics().get("crossUnitTotal"));
        assertEquals(35, risk.getScore());
    }

    @Test
    @SuppressWarnings("unchecked")
    void occupancyAndActionCenterShareIdenticalUnitFacts() {
        service.setRuntimeTimezone("Asia/Shanghai");
        String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "一致性项目", "0")));
        when(rentMapper.selectList(any())).thenReturn(Collections.singletonList(rent("P1", "D1", 1, day)));
        when(rentMaterialMapper.selectList(any())).thenReturn(Arrays.asList(
                material("P1", "D1", "M1", "根", 10), material("P1", "D1", "M2", "吨", 2)));
        com.zjyz.agent.workspace.tool.MaterialBalanceService balanceService = new com.zjyz.agent.workspace.tool.MaterialBalanceService(
                rentMapper, rentMaterialMapper, returnMapper, returnMaterialMapper, rentInMapper, rentInMaterialMapper,
                rentInReturnMapper, rentInReturnMaterialMapper);
        com.zjyz.agent.orch.AgentSkillExecution occupancy = new com.zjyz.agent.workspace.tool.ProjectMaterialOccupancySkill(
                projectMapper, balanceService, new com.fasterxml.jackson.databind.ObjectMapper()).execute("{}", "未归还材料", all(false));
        Result actions = service.generate(all(false), query("MATERIAL_OUTSTANDING"));
        Map<String, java.math.BigDecimal> occupancyGroups = (Map<String, java.math.BigDecimal>) occupancy.getCards().get(0).get("unitGroups");
        List<Map<String,Object>> riskGroups = (List<Map<String,Object>>) actions.getItems().get(0).getRisks().get(0).getRawMetrics().get("unitGroups");
        for (Map<String,Object> group : riskGroups) {
            assertEquals(0, occupancyGroups.get(group.get("unit")).compareTo((java.math.BigDecimal)group.get("outstandingQuantity")));
        }
        assertEquals(occupancyGroups.size(), riskGroups.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void actionCenterDoesNotAddUnknownUnitQuantities() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "缺单位项目", "0")));
        when(rentMapper.selectList(any())).thenReturn(Collections.singletonList(rent("P1", "D1", 1, "2026-08-30")));
        when(rentMaterialMapper.selectList(any())).thenReturn(Arrays.asList(
                material("P1", "D1", "M1", null, 10), material("P1", "D1", "M2", null, 5)));
        Result result = service.generate(all(false), query("MATERIAL_OUTSTANDING"));
        Map<String,Object> metrics = result.getItems().get(0).getRisks().get(0).getRawMetrics();
        assertTrue(((List<?>)metrics.get("unitGroups")).isEmpty());
        assertEquals(2L, metrics.get("missingUnitMaterialKinds"));
        assertEquals(2, metrics.get("outstandingMaterialKinds"));
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("不生成未知单位数量合计")));
    }

    @Test
    void allModeFiltersByProjectRiskIntersectionBeforeRanking() {
        when(projectMapper.selectList(any())).thenReturn(Arrays.asList(project("P1", "双风险", "0"), project("P2", "单风险", "0")));
        when(contractMapper.selectList(any())).thenReturn(Arrays.asList(
                contract("P1", "C1", "2026-07-01"), contract("P2", "C2", "2027-12-31")));
        when(rentMapper.selectList(any())).thenReturn(Arrays.asList(
                rent("P1", "D1", 0, "2026-08-01"), rent("P2", "D2", 0, "2026-08-01")));
        Query query = query("CONTRACT_EXPIRED", "PENDING_REVIEW");
        query.setMatchMode("ALL");

        Result result = service.generate(all(false), query);

        assertEquals(1, result.getProjectTotalCount());
        assertEquals("P1", result.getItems().get(0).getProjectId());
        assertTrue(result.getItems().get(0).getRiskTypes().containsAll(query.getRiskTypes()));
    }

    @Test
    void financeFailureDegradesDefaultAnyButMarksRankingIncomplete() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "待审项目", "0")));
        when(rentMapper.selectList(any())).thenReturn(Collections.singletonList(rent("P1", "D1", 0, "2026-08-01")));
        when(financeService.generate(any(), any(), any())).thenThrow(new MyBizException("orphan ledger", "FIN409"));

        Result result = service.generate(all(true), new Query());

        assertEquals(1, result.getFailedDimensions().size());
        assertTrue(result.getScoreIncomplete());
        assertEquals("AVAILABLE_DIMENSIONS", result.getRankingBasis());
        assertEquals(1, result.getProjectTotalCount());
    }

    @Test
    void explicitOnlyFailedFinanceDimensionFailsClosed() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "项目", "0")));
        when(financeService.generate(any(), any(), any())).thenThrow(new MyBizException("orphan ledger", "FIN409"));

        MyBizException error = assertThrows(MyBizException.class,
                () -> service.generate(all(true), query("OVERDUE_RECEIVABLE")));

        assertEquals("FIN409", error.getErrorCode());
    }

    @Test
    void financeDisabledOnlyDisablesReceivableWhileUnreconciledStillWorks() {
        ProjectEntity project = project("P1", "未对账项目", "0");
        project.setCreateDate("2026-06-01");
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project));
        Query query = query("UNRECONCILED", "OVERDUE_RECEIVABLE");

        Result result = service.generate(all(false), query);

        assertTrue(result.getItems().get(0).getRiskTypes().contains("UNRECONCILED"));
        assertEquals("OVERDUE_RECEIVABLE", result.getUnsupportedDimensions().get(0).getRiskType());
        assertTrue(result.getScoreIncomplete());
        assertEquals("AVAILABLE_DIMENSIONS", result.getRankingBasis());
        verify(financeService, never()).generate(any(), any(), any());
    }

    @Test
    void rentInOnlyScopeFailsClosedForRentOutOnlyDimensions() {
        ProjectEntity rentIn = project("P1", "租入项目", "0");
        rentIn.setProjectBusinessType("rent_in");
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(rentIn));

        MyBizException unreconciled = assertThrows(MyBizException.class,
                () -> service.generate(all(true), query("UNRECONCILED")));
        MyBizException receivable = assertThrows(MyBizException.class,
                () -> service.generate(all(true), query("OVERDUE_RECEIVABLE")));

        assertEquals("AGT409", unreconciled.getErrorCode());
        assertEquals("AGT409", receivable.getErrorCode());
        verify(financeService, never()).generate(any(), any(), any());
    }

    @Test
    void mixedScopeDisclosesPerProjectNotApplicableCoverage() {
        ProjectEntity rentIn = project("P2", "租入项目", "0");
        rentIn.setProjectBusinessType("rent_in");
        when(projectMapper.selectList(any())).thenReturn(Arrays.asList(project("P1", "租出项目", "0"), rentIn));

        Result result = service.generate(all(false), query("UNRECONCILED"));

        assertEquals(1, result.getProjectTotalCount());
        assertTrue(result.getScoreIncomplete());
        OwnerActionCenterModels.DimensionStatus status = result.getDimensionStatuses().get(0);
        assertEquals("PARTIALLY_APPLICABLE", status.getStatus());
        assertEquals(1, status.getApplicableProjectCount());
        assertEquals(1, status.getNonApplicableProjectCount());
        assertEquals(Collections.singletonList("P2"), status.getNonApplicableProjectIds());
        assertTrue(result.getWarnings().stream().anyMatch(value -> value.contains("NOT_APPLICABLE")));
    }

    @Test
    void mixedScopeReceivableCoverageDoesNotTreatRentInAsZeroRisk() {
        ProjectEntity rentIn = project("P2", "租入项目", "0");
        rentIn.setProjectBusinessType("rent_in");
        when(projectMapper.selectList(any())).thenReturn(Arrays.asList(project("P1", "租出项目", "0"), rentIn));
        FinanceLedgerSnapshotModels.DirectionSummary rentOut = new FinanceLedgerSnapshotModels.DirectionSummary();
        rentOut.setProjects(Collections.emptyList());
        FinanceLedgerSnapshotModels.Result snapshot = new FinanceLedgerSnapshotModels.Result();
        snapshot.setRentOut(rentOut);
        snapshot.setWarnings(Collections.emptyList());
        when(financeService.generate(any(), any(), any())).thenReturn(snapshot);

        Result result = service.generate(all(true), query("OVERDUE_RECEIVABLE"));

        assertEquals(0, result.getProjectTotalCount());
        assertTrue(result.getScoreIncomplete());
        OwnerActionCenterModels.DimensionStatus status = result.getDimensionStatuses().get(0);
        assertEquals("PARTIALLY_APPLICABLE", status.getStatus());
        assertEquals(Collections.singletonList("P2"), status.getNonApplicableProjectIds());
        assertEquals(1, status.getApplicableProjectCount());
    }

    @Test
    void unsupportedSingleOrAllIntersectionFailsClosedInsteadOfReturningNoRisk() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "项目", "0")));

        MyBizException single = assertThrows(MyBizException.class,
                () -> service.generate(all(false), query("INVENTORY_ANOMALY")));
        Query intersection = query("INVENTORY_ANOMALY", "PENDING_REVIEW");
        intersection.setMatchMode("ALL");
        MyBizException all = assertThrows(MyBizException.class,
                () -> service.generate(all(false), intersection));

        assertEquals("AGT409", single.getErrorCode());
        assertEquals("AGT409", all.getErrorCode());
    }

    @Test
    void unexpectedDimensionFailureDegradesDefaultAnyButExplicitDimensionFailsClosed() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "待审项目", "0")));
        when(contractMapper.selectList(any())).thenThrow(new IllegalStateException("database unavailable"));
        when(rentMapper.selectList(any())).thenReturn(Collections.singletonList(rent("P1", "D1", 0, "2026-08-01")));

        Result partial = service.generate(all(false), new Query());

        assertTrue(partial.getFailedDimensions().stream()
                .anyMatch(item -> "CONTRACT_EXPIRED".equals(item.getRiskType()) && "AGT500".equals(item.getErrorCode())));
        assertTrue(partial.getScoreIncomplete());
        assertEquals("AVAILABLE_DIMENSIONS", partial.getRankingBasis());
        assertEquals(1, partial.getProjectTotalCount());

        MyBizException explicit = assertThrows(MyBizException.class,
                () -> service.generate(all(false), query("CONTRACT_EXPIRED")));
        assertEquals("AGT500", explicit.getErrorCode());
    }

    @Test
    void pendingReviewCountsNullStatusAndInvalidCreateDateWithoutInventingAge() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "待审项目", "0")));
        when(rentMapper.selectList(any())).thenReturn(Collections.singletonList(rent("P1", "D1", null, "invalid")));

        Result result = service.generate(all(false), query("PENDING_REVIEW"));

        RiskFact risk = result.getItems().get(0).getRisks().get(0);
        assertEquals(1, risk.getRawMetrics().get("pendingReviewCount"));
        assertNull(risk.getRawMetrics().get("oldestPendingDays"));
        assertTrue(result.getWarnings().stream().anyMatch(value -> value.contains("不计算待审时长分")));
    }

    @Test
    void pendingReviewOnlyCountsZeroOrNullAndNeverFallsBackToBusinessDateForAge() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "待审项目", "0")));
        RentDocumentEntity pending = rent("P1", "D1", null, "2026-08-01");
        pending.setCreateDate("invalid");
        RentDocumentEntity invalidStatus = rent("P1", "D2", 2, "2026-07-01");
        when(rentMapper.selectList(any())).thenReturn(Arrays.asList(pending, invalidStatus));

        Result result = service.generate(all(false), query("PENDING_REVIEW"));

        RiskFact risk = result.getItems().get(0).getRisks().get(0);
        assertEquals(1, risk.getRawMetrics().get("pendingReviewCount"));
        assertNull(risk.getRawMetrics().get("oldestPendingDate"));
        assertNull(risk.getRawMetrics().get("oldestPendingDays"));
        assertTrue(result.getWarnings().stream().anyMatch(value -> value.contains("数据质量异常排除")));
    }

    @Test
    void projectAndRiskScoresEqualTheirContributions() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "风险项目", "0")));
        when(contractMapper.selectList(any())).thenReturn(Collections.singletonList(contract("P1", "C1", "2026-07-01")));
        when(rentMapper.selectList(any())).thenReturn(Collections.singletonList(rent("P1", "D1", 0, "2026-08-01")));

        ProjectAction action = service.generate(all(false), query("CONTRACT_EXPIRED", "PENDING_REVIEW")).getItems().get(0);

        assertEquals(action.getTotalScore(), action.getScoreContributions().stream().mapToInt(item -> item.getPoints()).sum());
        action.getRisks().forEach(risk -> assertEquals(risk.getScore().intValue(),
                risk.getScoreContributions().stream().mapToInt(item -> item.getPoints()).sum()));
    }

    @Test
    void rejectsUnknownRiskMatchAndLimit() {
        when(projectMapper.selectList(any())).thenReturn(Collections.emptyList());
        Query badRisk = query("UNKNOWN");
        assertEquals("AGT400", assertThrows(MyBizException.class, () -> service.generate(all(false), badRisk)).getErrorCode());
        Query badMatch = query("PENDING_REVIEW"); badMatch.setMatchMode("SOME");
        assertEquals("AGT400", assertThrows(MyBizException.class, () -> service.generate(all(false), badMatch)).getErrorCode());
        Query badLimit = query("PENDING_REVIEW"); badLimit.setLimit(101);
        assertEquals("AGT400", assertThrows(MyBizException.class, () -> service.generate(all(false), badLimit)).getErrorCode());
    }

    @Test
    void projectAndActionTruncationAreIndependent() {
        when(projectMapper.selectList(any())).thenReturn(Collections.singletonList(project("P1", "双风险", "0")));
        when(contractMapper.selectList(any())).thenReturn(Collections.singletonList(contract("P1", "C1", "2026-07-01")));
        when(rentMapper.selectList(any())).thenReturn(Collections.singletonList(rent("P1", "D1", 0, "2026-08-01")));
        Query query = query("CONTRACT_EXPIRED", "PENDING_REVIEW");
        query.setLimit(1);

        Result result = service.generate(all(false), query);

        assertFalse(result.getTruncated());
        assertTrue(result.getActionTruncated());
        assertEquals(result.getTotalCount(), result.getDisplayedCount());
        assertEquals(2, result.getActionTotalCount());
        assertEquals(1, result.getActionDisplayedCount());
    }

    private Query query(String... risks) {
        Query query = new Query();
        query.setRiskTypes(Arrays.asList(risks));
        return query;
    }

    private AgentRuntimeRecords.Workspace all(boolean financeEnabled) {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("CID-A");
        workspace.setSelectionMode("ALL");
        workspace.setProjectIds(Collections.emptyList());
        workspace.setFinanceEnabled(financeEnabled);
        return workspace;
    }

    private ProjectEntity project(String id, String name, String status) {
        ProjectEntity value = new ProjectEntity();
        value.setProjectId(id); value.setCid("CID-A"); value.setProjectName(name);
        value.setProjectBusinessType("rent_out"); value.setProjectStatusFlag(status);
        value.setManagerName("负责人" + id); value.setCreateDate("2026-01-01");
        return value;
    }

    private ContractEntity contract(String projectId, String id, String endDate) {
        ContractEntity value = new ContractEntity();
        value.setProjectId(projectId); value.setContractId(id); value.setEndDate(endDate); value.setCreateDate("2026-01-01");
        return value;
    }

    private RentDocumentEntity rent(String projectId, String id, Integer reviewStatus, String date) {
        RentDocumentEntity value = new RentDocumentEntity();
        value.setProjectId(projectId); value.setCid("CID-A"); value.setRentDocumentId(id);
        value.setReviewStatus(reviewStatus); value.setRentDate(date); value.setCreateDate(date);
        return value;
    }

    private RentDocumentMaterialEntity material(String projectId, String documentId, String materialId, String unit, int quantity) {
        RentDocumentMaterialEntity value = new RentDocumentMaterialEntity();
        value.setProjectId(projectId); value.setCid("CID-A"); value.setDocumentId(documentId); value.setMaterialId(materialId);
        value.setCountingUnit(unit); value.setCountingQuantity((double) quantity); value.setMaterialBusinessType("RENTAL");
        return value;
    }
}
