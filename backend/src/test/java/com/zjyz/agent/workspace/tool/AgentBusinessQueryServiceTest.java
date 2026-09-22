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

}
