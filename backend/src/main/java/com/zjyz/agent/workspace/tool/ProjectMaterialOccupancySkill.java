package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.ProjectMapper;
import com.zjyz.pojo.entity.ProjectEntity;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class ProjectMaterialOccupancySkill {
 public static final String TOOL_CODE="project.material_occupancy";
 private final ProjectMapper projects;
 private final MaterialBalanceService balances;
 private final ObjectMapper json;
 @Value("${agent.runtime.timezone:${AGENT_TIMEZONE:Asia/Shanghai}}") private String timezone="Asia/Shanghai";
 public ProjectMaterialOccupancySkill(ProjectMapper projects, MaterialBalanceService balances,ObjectMapper json){this.projects=projects;this.balances=balances;this.json=json;}
 public AgentSkillExecution execute(String args,String message,AgentRuntimeRecords.Workspace w){
  if(w==null||!StringUtils.hasText(w.getCid()))throw new MyBizException("工作空间缺失","AGT400");
  JsonNode q;try{q=json.readTree(StringUtils.hasText(args)?args:"{}");}catch(Exception e){throw new MyBizException("材料余额参数不正确","AGT400");}
  LocalDate today=LocalDate.now(ZoneId.of(timezone));
  if(q.hasNonNull("asOfDate")&&!today.toString().equals(q.path("asOfDate").asText()))throw new MyBizException("当前材料余额仅支持今天，不能恢复历史审核状态","AGT400");
  QueryWrapper<ProjectEntity> filter=new QueryWrapper<ProjectEntity>().eq("cid",w.getCid());
  if("EXPLICIT".equalsIgnoreCase(w.getSelectionMode())){if(w.getProjectIds()==null||w.getProjectIds().isEmpty())throw new MyBizException("项目范围为空","AGT400");filter.in("project_id",w.getProjectIds());}
  else if(!"ALL".equalsIgnoreCase(w.getSelectionMode()))throw new MyBizException("项目范围无效","AGT400");
  String status=q.path("projectStatus").asText("");
  if("ONGOING".equals(status)||"0".equals(status))filter.eq("project_status_flag","0");
  else if("COMPLETED".equals(status)||"1".equals(status))filter.eq("project_status_flag","1");
  else if(!status.isEmpty()&&!"ALL".equals(status))throw new MyBizException("项目状态无效","AGT400");
  if(q.hasNonNull("keyword")&&StringUtils.hasText(q.path("keyword").asText())) {
   String keyword=q.path("keyword").asText().trim();
   filter.and(v->v.like("project_name",keyword).or().like("manager_name",keyword)
       .or().like("partner_name",keyword).or().like("tenant_unit",keyword));
  }
  Map<String,ProjectEntity> projectMap=projects.selectList(filter).stream().filter(p->w.getCid().equals(p.getCid())).collect(Collectors.toMap(ProjectEntity::getProjectId,p->p,(a,b)->a,LinkedHashMap::new));
  MaterialBalanceService.Result result=balances.query(w.getCid(),projectMap,today);
  List<Map<String,Object>> rows=new ArrayList<>();Map<String,BigDecimal> totals=new TreeMap<>();int anomalies=0;int missingUnitMaterials=0;
  for(MaterialBalanceService.Balance b:result.balances.values()){
   if(b.outstanding().signum()<0)anomalies++;
   if(q.path("onlyWithOutstanding").asBoolean(true)&&b.outstanding().signum()==0)continue;
   boolean unitMissing=b.unitMissing||!StringUtils.hasText(b.unit)||"未填写单位".equals(b.unit);
   if(unitMissing)missingUnitMaterials++;
   Map<String,Object> row=map("projectId",b.projectId,"projectName",projectMap.get(b.projectId).getProjectName(),"materialId",b.materialId,"materialName",b.name,"materialSpecification",b.specification,"materialUnit",b.unit,"unitMissing",unitMissing,"rentedQuantity",b.rented,"returnedQuantity",b.returned,"outstandingQuantity",b.outstanding(),"anomaly",b.outstanding().signum()<0,"sourceDocumentIds",b.sourceDocumentIds);
   rows.add(row);if(b.outstanding().signum()>0&&!unitMissing)totals.merge(b.unit,b.outstanding(),BigDecimal::add);
  }
  rows.sort(Comparator.comparing(r->r.get("projectId")+"|"+r.get("materialId")+"|"+r.get("materialUnit")));
  int limit=Math.max(1,Math.min(500,q.path("limit").asInt(100)));int offset=Math.max(0,q.path("offset").asInt(0));
  List<Map<String,Object>> shown=rows.subList(Math.min(offset,rows.size()),Math.min(rows.size(),Math.min(offset,rows.size())+limit));
  Map<String,Object> criteria=map("metricId","material.outstanding_physical_quantity","definitionVersion","2","availability",missingUnitMaterials>0?"MISSING_DATA":"AVAILABLE","completeness",missingUnitMaterials>0?"PARTIAL":"COMPLETE","dateBasis","BUSINESS_DATE","reviewedOnly",true,"excludedDocuments",result.excludedDocuments,"totalRows",rows.size(),"previewRows",shown.size(),"unitPolicy","GROUP_BY_KNOWN_UNIT","missingUnitMaterials",missingUnitMaterials,"asOfDate",today.toString());
  if(StringUtils.hasText(status))criteria.put("projectStatus",status);
  if(q.hasNonNull("keyword")&&StringUtils.hasText(q.path("keyword").asText()))criteria.put("keyword",q.path("keyword").asText().trim());
  criteria.put("onlyWithOutstanding",q.path("onlyWithOutstanding").asBoolean(true));
  Map<String,Object> card=map("type","business-query-result","schemaVersion","2","metricId","material.outstanding_physical_quantity","items",shown,"totalCount",rows.size(),"displayedCount",shown.size(),"truncated",shown.size()<rows.size(),"nextOffset",offset+shown.size()<rows.size()?offset+shown.size():null,"unitGroups",totals,"negativeBalanceCount",anomalies,"missingUnitMaterials",missingUnitMaterials,"totalOutstandingQuantity",null,"asOfDate",today.toString(),"criteria",criteria);
  AgentEvidence evidence=new AgentEvidence();evidence.setToolCode(TOOL_CODE);evidence.setCriteria(criteria);evidence.setRecordCount(result.sourceRows);evidence.setTimeRange("asOf="+today);
  evidence.setMetricId("material.outstanding_physical_quantity");evidence.setDefinitionVersion("2");
  evidence.setTimeBasis("BUSINESS_DATE");evidence.setAvailability(missingUnitMaterials>0?"MISSING_DATA":"AVAILABLE");evidence.setCompleteness(missingUnitMaterials>0?"PARTIAL":"COMPLETE");
  evidence.setQueriedAt(java.time.OffsetDateTime.now().toString());evidence.setGrain(Arrays.asList("projectId","materialId","materialSpecification","materialUnit"));
  AgentSkillExecution out=new AgentSkillExecution();out.setIntent("project_material_occupancy");out.setConfidence(1d);out.setCards(Collections.singletonList(card));out.setEvidence(evidence);
  out.setAnswer("截至 "+today+"，已复核有效单据的实物未归还余额按单位为："+(totals.isEmpty()?(missingUnitMaterials>0?"无可汇总的已知单位余额":"无正余额"):totals.entrySet().stream().map(e->e.getValue().toPlainString()+" "+e.getKey()).collect(Collectors.joining("、")))+"。材料明细共 "+rows.size()+" 行，当前展示 "+shown.size()+" 行；负余额异常 "+anomalies+" 项。缺单位材料 "+missingUnitMaterials+" 行已保留明细，未合计未知量纲。赔偿后的归还义务和在租计费量不在此指标中。");
  out.setWarnings(Collections.singletonList("仅采用截至今天已复核单据；排除买断租出材料；不恢复历史状态。排除非已复核或无效/未来日期单据 "+result.excludedDocuments+" 张。"));return out;
 }
 private Map<String,Object> map(Object... a){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<a.length;i+=2)m.put(a[i].toString(),a[i+1]);return m;}
}
