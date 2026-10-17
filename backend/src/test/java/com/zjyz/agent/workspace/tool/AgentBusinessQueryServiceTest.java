package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.security.AuthContext;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.pojo.param.req.*;
import com.zjyz.pojo.param.ret.*;
import com.zjyz.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentBusinessQueryServiceTest {
    final AgentBusinessQueryService service=new AgentBusinessQueryService();
    final MarketService market=mock(MarketService.class);
    final InventoryService inventory=mock(InventoryService.class);
    final ProjectService projects=mock(ProjectService.class);
    final AgentRuntimeRecords.Workspace scope=new AgentRuntimeRecords.Workspace();
    @BeforeEach void setup() {
        AuthContext.set("u1","c1");scope.setCid("c1");scope.setScopeType("TENANT");
        scope.setSelectionMode("EXPLICIT");scope.setProjectIds(Collections.emptyList());
        ReflectionTestUtils.setField(service,"json",new ObjectMapper());
        ReflectionTestUtils.setField(service,"market",market);
        ReflectionTestUtils.setField(service,"inventory",inventory);
        ReflectionTestUtils.setField(service,"projects",projects);
    }
    @AfterEach void cleanup() {AuthContext.clear();}
    @Test void noProjectMarketUsesPublicServiceAndReportsPagination() {
        MarketMaterialListRet result=new MarketMaterialListRet();result.setTotalNum(58L);
        when(market.queryMaterialList(any())).thenReturn(result);
        AgentSkillExecution execution=service.execute("market.search","{\"keyword\":\"钢管\",\"city\":\"杭州\",\"pageNum\":2}",scope);
        ArgumentCaptor<QueryMarketMaterialListParam> arg=ArgumentCaptor.forClass(QueryMarketMaterialListParam.class);
        verify(market).queryMaterialList(arg.capture());
        assertEquals("杭州",arg.getValue().getCity());assertEquals(20,arg.getValue().getPageSize());
        assertEquals("PUBLIC_MARKET",execution.getEvidence().getScopeType());
        assertTrue(execution.getEvidence().getProjectIds().isEmpty());
        assertTrue(execution.getAnswer().contains("58"));assertTrue(execution.getAnswer().contains("第 2 页"));
        verifyNoInteractions(projects,inventory);
    }
    @Test void tenantInventoryDoesNotDependOnProjectIds() {
        when(inventory.queryInventoryMaterials(any())).thenReturn(new InventoryMaterialListRet());
        AgentSkillExecution result=service.execute("inventory.materials","{}",scope);
        assertEquals("TENANT_INVENTORY",result.getEvidence().getScopeType());
        verify(inventory).queryInventoryMaterials(any());verifyNoInteractions(projects);
    }
    @ParameterizedTest @ValueSource(strings={"{\"cid\":\"other\"}","{\"pageNum\":0}","{\"pageSize\":9999}",
        "{\"pageNum\":1.2}","{\"keyword\":{\"sql\":\"select\"}}","{\"sortField\":\"price;drop\"}","null"})
    void refusesUnregisteredOrInvalidInputsBeforeService(String args) {
        assertThrows(MyBizException.class,()->service.execute("market.search",args,scope));verifyNoInteractions(market);
    }
    @Test void tenantMismatchCannotCallAnyService() {
        scope.setCid("other");assertThrows(MyBizException.class,()->service.execute("market.cities","{}",scope));
        verifyNoInteractions(market);
    }
    @Test void projectSelectionCannotBeExpandedByArguments() {
        scope.setProjectIds(Collections.singletonList("p1"));
        assertThrows(MyBizException.class,()->service.execute("project.detail","{\"projectId\":\"p2\"}",scope));
        verifyNoInteractions(projects);
    }
    @Test void contractChecksLiveProjectOwnershipBeforeReadingContract() {
        scope.setProjectIds(Collections.singletonList("p1"));
        ContractService contracts=mock(ContractService.class);ReflectionTestUtils.setField(service,"contracts",contracts);
        when(projects.queryProjectInfo("p1")).thenThrow(new MyBizException("无权限","403"));
        assertThrows(MyBizException.class,()->service.execute("contract.detail","{\"projectId\":\"p1\"}",scope));
        verifyNoInteractions(contracts);
    }
    @Test void financeEntitlementCheckedEvenWithProjectAccess() {
        scope.setProjectIds(Collections.singletonList("p1"));scope.setFinanceEnabled(false);
        assertThrows(MyBizException.class,()->service.execute("finance.customer_payments","{\"projectId\":\"p1\"}",scope));
        verifyNoInteractions(projects);
    }
    @ParameterizedTest @ValueSource(strings={"RENT_OUT","RETURN","COMPENSATION","RENT_IN","RENT_IN_RETURN"})
    void allFiveDocumentListsReuseStrategyWithFrozenProject(String type) {
        scope.setProjectIds(Collections.singletonList("p1"));
        DocumentStrategyFactory factory=mock(DocumentStrategyFactory.class);
        ReflectionTestUtils.setField(service,"documentStrategies",factory);
        when(factory.queryDocumentList(any())).thenReturn(new DocumentListInfoRet());
        service.execute("document.list_records","{\"projectId\":\"p1\",\"documentType\":\""+type+"\"}",scope);
        ArgumentCaptor<QueryDocumentListParam> arg=ArgumentCaptor.forClass(QueryDocumentListParam.class);
        verify(factory).queryDocumentList(arg.capture());assertEquals("p1",arg.getValue().getProjectId());
        assertEquals(Map.of("RENT_OUT","0","RETURN","1","COMPENSATION","2","RENT_IN","9","RENT_IN_RETURN","10").get(type),arg.getValue().getQueryType());
    }
    @Test void documentOfAnotherSelectedProjectIsNotReturnedForRequestedProject() {
        scope.setProjectIds(Arrays.asList("p1","p2"));
        RentDocumentService rent=mock(RentDocumentService.class);ReflectionTestUtils.setField(service,"rent",rent);
        RentInfoRet record=new RentInfoRet();record.setProjectId("p2");when(rent.queryRentDocument("d1")).thenReturn(record);
        assertThrows(MyBizException.class,()->service.execute("document.detail","{\"projectId\":\"p1\",\"documentType\":\"RENT_OUT\",\"documentId\":\"d1\"}",scope));
    }
    @Test void registeredToolsUseExactSchemaAndNoArbitraryEndpoint() {
        AgentToolCatalog catalog=new AgentToolCatalog();
        for(AgentBusinessCapabilities.Definition d:AgentBusinessCapabilities.definitions()) {
            assertEquals(d.code,catalog.find(d.modelName()).getToolCode());
            assertEquals(Boolean.FALSE,d.parameters.get("additionalProperties"));
            assertFalse(d.properties.containsKey("url"));assertFalse(d.properties.containsKey("sql"));
            assertFalse(d.properties.containsKey("cid"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"RENT_OUT","RETURN","COMPENSATION","RENT_IN","RENT_IN_RETURN"})
    void exportsAllFiveDocumentsAsDownloadableWorkbooksAfterProjectCheck(String type) throws Exception {
        scope.setProjectIds(List.of("p1"));
        org.apache.poi.xssf.usermodel.XSSFWorkbook book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();
        book.createSheet("单据").createRow(0).createCell(0).setCellValue("钢管");
        switch(type) {
            case "RENT_OUT": {
                RentDocumentService delegate=mock(RentDocumentService.class);ReflectionTestUtils.setField(service,"rent",delegate);
                RentInfoRet info=new RentInfoRet();info.setProjectId("p1");
                when(delegate.queryRentDocument("d1")).thenReturn(info);
                when(delegate.exportRentDocumentExcel("d1")).thenReturn(book); break;
            }
            case "RETURN": {
                ReturnDocumentService delegate=mock(ReturnDocumentService.class);ReflectionTestUtils.setField(service,"returns",delegate);
                ReturnInfoRet info=new ReturnInfoRet();info.setProjectId("p1");
                when(delegate.queryReturnDocument("d1")).thenReturn(info);
                when(delegate.exportReturnDocumentExcel("d1")).thenReturn(book); break;
            }
            case "COMPENSATION": {
                CompensationDocumentService delegate=mock(CompensationDocumentService.class);ReflectionTestUtils.setField(service,"compensation",delegate);
                CompensationInfoRet info=new CompensationInfoRet();info.setProjectId("p1");
                when(delegate.queryCompensationDocument("d1")).thenReturn(info);
                when(delegate.exportCompensationDocumentExcel("d1")).thenReturn(book); break;
            }
            case "RENT_IN": {
                RentInDocumentService delegate=mock(RentInDocumentService.class);ReflectionTestUtils.setField(service,"rentIn",delegate);
                RentInInfoRet info=new RentInInfoRet();info.setProjectId("p1");
                when(delegate.queryRentInDocument("d1")).thenReturn(info);
                when(delegate.exportRentInDocumentExcel("d1")).thenReturn(book); break;
            }
            case "RENT_IN_RETURN": {
                RentInReturnDocumentService delegate=mock(RentInReturnDocumentService.class);ReflectionTestUtils.setField(service,"rentInReturn",delegate);
                RentInReturnInfoRet info=new RentInReturnInfoRet();info.setProjectId("p1");
                when(delegate.queryRentInReturnDocument("d1")).thenReturn(info);
                when(delegate.exportRentInReturnDocumentExcel("d1")).thenReturn(book); break;
            }
            default:fail("未覆盖单据类型");
        }
        AgentSkillExecution result=service.execute("document.export","{\"projectId\":\"p1\",\"documentType\":\""+type+"\",\"documentId\":\"d1\"}",scope);
        assertEquals("BUSINESS_EXCEL",result.getArtifactType());
        AgentRuntimeRecords.Artifact artifact=new AgentRuntimeRecords.Artifact();artifact.setArtifactType(result.getArtifactType());artifact.setContentJson(result.getArtifactContentJson());
        byte[] bytes=new com.zjyz.agent.workspace.finance.ReceivableCollectionArtifactExporter(new ObjectMapper()).export(artifact);
        try(org.apache.poi.ss.usermodel.Workbook downloaded=org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            assertEquals("钢管",downloaded.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
        }
        assertFalse(new ObjectMapper().writeValueAsString(result.getCards()).contains("base64"));
    }

    @Test
    void phase2SettlementAndReconciliationAndLateFeePreviewsEnforceScopeAndReturnHitlDrafts() {
        scope.setProjectIds(List.of("p1"));
        scope.setFinanceEnabled(true);
        SettlementDocumentService settlements = mock(SettlementDocumentService.class);
        ReconciliationDocumentService reconciliation = mock(ReconciliationDocumentService.class);
        LateFeeDocumentService lateFees = mock(LateFeeDocumentService.class);
        ReflectionTestUtils.setField(service, "settlements", settlements);
        ReflectionTestUtils.setField(service, "reconciliation", reconciliation);
        ReflectionTestUtils.setField(service, "lateFees", lateFees);

        SettlementPeriodListRet cycles = new SettlementPeriodListRet("NATURAL_MONTH",
                List.of(new SettlementPeriodListRet.SettlementPeriod("2026-09-01", "2026-09-30")));
        when(settlements.querySettlementCycleList("p1")).thenReturn(cycles);
        SettlementPreviewRet sp = new SettlementPreviewRet();
        sp.setDocumentName("示范工地A结算单");
        SettlementPreviewRet.Summary summary = new SettlementPreviewRet.Summary();
        summary.setCurrentRentFee(new java.math.BigDecimal("10000.00"));
        summary.setCompensationFee(new java.math.BigDecimal("1500.00"));
        summary.setMiscFee(new java.math.BigDecimal("1000.00"));
        summary.setFinalReceivableAmount(new java.math.BigDecimal("12500.00"));
        sp.setSummary(summary);
        when(settlements.previewSettlement(any())).thenReturn(sp);

        AgentSkillExecution settlementExec = service.execute("settlement.preview_draft", "{}", scope);
        assertTrue(settlementExec.getAnswer().contains("【30秒结论】"));
        assertTrue(settlementExec.getAnswer().contains("12,500.00"));
        assertEquals("PROJECT_WORKFLOW", settlementExec.getCards().get(0).get("navigationTarget"));

        ReconciliationPreviewRet rp = new ReconciliationPreviewRet();
        rp.setTitle("示范工地A物料对账单");
        ReconciliationPreviewRet.Section sec = new ReconciliationPreviewRet.Section();
        sec.setTitle("盘扣架");
        sec.setRows(List.of(Map.of("materialName", "盘扣立杆", "closingQty", 130)));
        rp.setSections(List.of(sec));
        when(reconciliation.previewReconciliationDocument(any())).thenReturn(rp);

        AgentSkillExecution recExec = service.execute("reconciliation.preview_draft", "{\"projectId\":\"p1\"}", scope);
        assertTrue(recExec.getAnswer().contains("物料对账单试算草稿"));
        assertEquals("PROJECT_WORKFLOW", recExec.getCards().get(0).get("navigationTarget"));

        LateFeeDocumentRet lp = new LateFeeDocumentRet();
        lp.setDocumentName("示范工地A滞纳金");
        lp.setCumulativeAssessedAmount(new java.math.BigDecimal("680.50"));
        lp.setCumulativePaidAmount(new java.math.BigDecimal("100.00"));
        lp.setCurrentOutstandingAmount(new java.math.BigDecimal("580.50"));
        lp.setPeriods(List.of(new LateFeeDocumentRet.Period()));
        when(lateFees.preview(any())).thenReturn(lp);

        AgentSkillExecution lateExec = service.execute("finance.late_fee_trial", "{\"projectId\":\"p1\",\"dailyRatePercent\":\"0.05\"}", scope);
        assertTrue(lateExec.getAnswer().contains("580.50"));
    }

    @Test
    void phase2MaterialGovernanceAndPhase3CopilotAndAdvisorExecuteDeterministically() {
        scope.setProjectIds(List.of("p1", "p2"));
        scope.setFinanceEnabled(true);

        MaterialGovernanceService gov = mock(MaterialGovernanceService.class);
        MaterialService matService = mock(MaterialService.class);
        ReflectionTestUtils.setField(service, "materialGovernance", gov);
        ReflectionTestUtils.setField(service, "materials", matService);
        MaterialInfoRet mList = new MaterialInfoRet();
        MaterialInfoRet.MaterialInfo m1 = new MaterialInfoRet.MaterialInfo();
        m1.setMid("m1"); m1.setMaterialName("钢管"); m1.setMaterialSpecification("48*3.0");
        mList.addMaterialInfo(m1);
        when(matService.queryMaterialInfo(any())).thenReturn(mList);

        AgentSkillExecution govExec = service.execute("governance.material_alias_audit", "{}", scope);
        assertEquals("MATERIALS", govExec.getCards().get(0).get("navigationTarget"));
        assertTrue(govExec.getAnswer().contains("主数据缺失单位 1 项"));

        // Copilot: Order fulfillment with estimateId
        MaterialEstimateService est = mock(MaterialEstimateService.class);
        ReflectionTestUtils.setField(service, "estimates", est);
        MaterialEstimateMatchRet matchRet = new MaterialEstimateMatchRet();
        matchRet.setEstimateId("est-1");
        matchRet.setEnoughCount(3);
        matchRet.setShortageCount(1);
        matchRet.setTotalGapQty(new java.math.BigDecimal("200"));
        when(est.queryEstimateMatch("est-1")).thenReturn(matchRet);
        MarketRecommendRet recRet = new MarketRecommendRet();
        recRet.setShortageItems(List.of(new MarketRecommendRet.ShortageItem()));
        when(market.recommendForEstimate(any())).thenReturn(recRet);

        AgentSkillExecution evalExec = service.execute("copilot.order_fulfillment_eval", "{\"estimateId\":\"est-1\"}", scope);
        assertTrue(evalExec.getAnswer().contains("75.0%"));
        assertEquals("MARKET", evalExec.getCards().get(0).get("navigationTarget"));

        // Copilot: Price breakeven sandbox
        com.zjyz.dao.ProjectMaterialPricingMapper pricingMapper = mock(com.zjyz.dao.ProjectMaterialPricingMapper.class);
        ReflectionTestUtils.setField(service, "projectMaterialPricingMapper", pricingMapper);
        com.zjyz.pojo.entity.ProjectMaterialPricingEntity pEntity = new com.zjyz.pojo.entity.ProjectMaterialPricingEntity();
        pEntity.setMid("m1"); pEntity.setDailyRent(new java.math.BigDecimal("2.0")); pEntity.setPredictRentNumber(500);
        when(pricingMapper.selectList(any())).thenReturn(List.of(pEntity));

        AgentSkillExecution sandboxExec = service.execute("copilot.price_breakeven_sandbox",
                "{\"projectId\":\"p1\",\"discountPercent\":90,\"freeRentDays\":5,\"leaseDays\":60}", scope);
        assertTrue(sandboxExec.getAnswer().contains("1,000.00 元/天"));

        // Advisor: BCG matrix
        InventoryMaterialListRet invList = new InventoryMaterialListRet();
        InventoryMaterialItemRet deadItem = new InventoryMaterialItemRet();
        deadItem.setMaterialId("m-dead");
        deadItem.setMaterialName("冷门扣件");
        deadItem.setMaterialSpecification("异形");
        deadItem.setInventoryUnit("个");
        deadItem.setInventoryQuantity(800);
        deadItem.setRentedQuantity(0);
        deadItem.setLastFlowDate("2026-01-01");
        invList.setList(List.of(deadItem));
        when(inventory.queryInventoryMaterials(any())).thenReturn(invList);

        AgentSkillExecution bcgExec = service.execute("advisor.material_bcg_matrix", "{\"deadStockDays\":60}", scope);
        assertTrue(bcgExec.getAnswer().contains("瘦狗死库存区 1 项"));
        assertEquals("INVENTORY", bcgExec.getCards().get(0).get("navigationTarget"));
    }

    @Test
    void singleProjectAutoFillsProjectIdAndClarificationCarriesNonNullEvidence() {
        scope.setProjectIds(Collections.singletonList("p1"));
        ProjectDetailInfoRet pInfo = new ProjectDetailInfoRet();
        pInfo.setProjectId("p1");
        pInfo.setPartnerName("单项目自动补全");
        when(projects.queryProjectInfo("p1")).thenReturn(pInfo);

        AgentSkillExecution detailExec = service.execute("project.detail", "{}", scope);
        assertFalse(Boolean.TRUE.equals(detailExec.getNeedClarification()));
        assertEquals(List.of("p1"), detailExec.getEvidence().getProjectIds());

        scope.setProjectIds(Arrays.asList("p1", "p2"));
        AgentSkillExecution clarifyExec = service.execute("project.detail", "{}", scope);
        assertTrue(Boolean.TRUE.equals(clarifyExec.getNeedClarification()));
        assertNotNull(clarifyExec.getEvidence());
        assertEquals("project.detail", clarifyExec.getEvidence().getToolCode());
    }

}
