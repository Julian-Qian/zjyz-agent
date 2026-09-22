package com.zjyz.agent.workspace.tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.dao.*;
import com.zjyz.pojo.entity.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class ProjectMaterialOccupancySkillTest {
 @Test void separatesUnitsAndKeepsFullTotalsWhenPageIsTruncated(){
  ProjectMapper pm=mock(ProjectMapper.class);MaterialBalanceService service=mock(MaterialBalanceService.class);
  ProjectEntity p=new ProjectEntity();p.setCid("c");p.setProjectId("p");p.setProjectName("项目");when(pm.selectList(any())).thenReturn(Collections.singletonList(p));
  MaterialBalanceService.Result result=new MaterialBalanceService.Result();
  for(int i=0;i<3;i++){MaterialBalanceService.Balance b=new MaterialBalanceService.Balance();b.projectId="p";b.materialId="m"+i;b.unit=i==0?"根":"套";b.rented=new BigDecimal(i==2?"-2":"10");result.balances.put("m"+i,b);}
  when(service.query(eq("c"),anyMap(),any())).thenReturn(result);
  AgentRuntimeRecords.Workspace w=new AgentRuntimeRecords.Workspace();w.setCid("c");w.setSelectionMode("EXPLICIT");w.setProjectIds(Collections.singletonList("p"));
  AgentSkillExecution out=new ProjectMaterialOccupancySkill(pm,service,new ObjectMapper()).execute("{\"limit\":1}","",w);
  Map<String,Object> card=out.getCards().get(0);assertNull(card.get("totalOutstandingQuantity"));assertEquals(3,card.get("totalCount"));assertEquals(1,card.get("displayedCount"));assertEquals(1,card.get("negativeBalanceCount"));assertEquals(true,card.get("truncated"));
  assertEquals(new BigDecimal("10"),((Map<?,?>)card.get("unitGroups")).get("套"));
 }
 @Test void unknownUnitsKeepRowsButNeverProduceAnUnknownQuantitySum(){
  ProjectMapper pm=mock(ProjectMapper.class);MaterialBalanceService service=mock(MaterialBalanceService.class);
  ProjectEntity p=new ProjectEntity();p.setCid("c");p.setProjectId("p");when(pm.selectList(any())).thenReturn(List.of(p));
  MaterialBalanceService.Result result=new MaterialBalanceService.Result();
  for(int i=0;i<2;i++){MaterialBalanceService.Balance b=new MaterialBalanceService.Balance();b.projectId="p";b.materialId="m"+i;b.unit="未填写单位";b.unitMissing=true;b.rented=new BigDecimal(i==0?"10":"5");result.balances.put("m"+i,b);}
  when(service.query(eq("c"),anyMap(),any())).thenReturn(result);
  AgentRuntimeRecords.Workspace w=new AgentRuntimeRecords.Workspace();w.setCid("c");w.setSelectionMode("EXPLICIT");w.setProjectIds(List.of("p"));
  AgentSkillExecution out=new ProjectMaterialOccupancySkill(pm,service,new ObjectMapper()).execute("{}","",w);
  assertEquals(2,out.getCards().get(0).get("missingUnitMaterials"));assertEquals(2,((List<?>)out.getCards().get(0).get("items")).size());
  assertTrue(((Map<?,?>)out.getCards().get(0).get("unitGroups")).isEmpty());
  assertEquals("PARTIAL",out.getEvidence().getCompleteness());assertFalse(out.getAnswer().contains("15"));
 }
 @Test void keywordFiltersProjectManagerAndCustomerInsideTenantAndFrozenScope(){
  ProjectMapper pm=mock(ProjectMapper.class);MaterialBalanceService service=mock(MaterialBalanceService.class);
  when(pm.selectList(any())).thenReturn(Collections.emptyList());when(service.query(eq("c"),anyMap(),any())).thenReturn(new MaterialBalanceService.Result());
  AgentRuntimeRecords.Workspace w=new AgentRuntimeRecords.Workspace();w.setCid("c");w.setSelectionMode("EXPLICIT");w.setProjectIds(List.of("p"));
  AgentSkillExecution out=new ProjectMaterialOccupancySkill(pm,service,new ObjectMapper()).execute("{\"keyword\":\"张三\"}","",w);
  org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper> filter=org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
  verify(pm).selectList(filter.capture());String sql=filter.getValue().getSqlSegment();
  assertTrue(sql.contains("cid ="));assertTrue(sql.contains("project_id IN"));
  assertTrue(sql.contains("project_name LIKE")&&sql.contains("manager_name LIKE")&&sql.contains("partner_name LIKE")&&sql.contains("tenant_unit LIKE"));
  assertTrue(sql.contains("AND (project_name"));assertTrue(filter.getValue().getParamNameValuePairs().containsValue("%张三%"));
  assertEquals("张三",out.getEvidence().getCriteria().get("keyword"));
 }
}
