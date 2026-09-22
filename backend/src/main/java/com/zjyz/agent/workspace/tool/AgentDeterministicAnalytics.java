package com.zjyz.agent.workspace.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.dao.*;
import com.zjyz.pojo.entity.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import java.time.*;
import java.math.*;
import java.util.*;
import java.util.stream.Collectors;

/** Deterministic, scoped facts; no model-generated formulas or SQL. */
@Component
public class AgentDeterministicAnalytics {
 private final ProjectMapper projects;private final ContractMapper contracts;private final SettlementDocumentMapper settlements;private final SettlementRentFeeSegmentMapper segments;private final RentDocumentMapper rents;private final RentInDocumentMapper rentIns;private final ReturnDocumentMapper returns;private final RentInReturnDocumentMapper rentInReturns;private final ObjectMapper json;
 @Value("${agent.runtime.timezone:${AGENT_TIMEZONE:Asia/Shanghai}}") private String timezone="Asia/Shanghai";
 public AgentDeterministicAnalytics(ProjectMapper p,ContractMapper c,SettlementDocumentMapper s,SettlementRentFeeSegmentMapper f,RentDocumentMapper r,RentInDocumentMapper i,ReturnDocumentMapper ret,RentInReturnDocumentMapper inRet,ObjectMapper j){projects=p;contracts=c;settlements=s;segments=f;rents=r;rentIns=i;returns=ret;rentInReturns=inRet;json=j;}
 public AgentSkillExecution execute(String code,String args,AgentRuntimeRecords.Workspace w){
  if(w==null||!StringUtils.hasText(w.getCid()))throw new MyBizException("工作空间缺失","AGT400");
  JsonNode q;try{q=json.readTree(StringUtils.hasText(args)?args:"{}");}catch(Exception e){throw new MyBizException("分析参数格式无效","AGT400");}
  QueryWrapper<ProjectEntity> scope=new QueryWrapper<ProjectEntity>().eq("cid",w.getCid());
  if("EXPLICIT".equalsIgnoreCase(w.getSelectionMode())){if(w.getProjectIds()==null||w.getProjectIds().isEmpty())throw new MyBizException("冻结范围为空","AGT400");scope.in("project_id",w.getProjectIds());}
  else if(!"ALL".equalsIgnoreCase(w.getSelectionMode()))throw new MyBizException("项目范围无效","AGT400");
  List<ProjectEntity> ps=projects.selectList(scope).stream().filter(p->w.getCid().equals(p.getCid())&&(!"EXPLICIT".equalsIgnoreCase(w.getSelectionMode())||w.getProjectIds().contains(p.getProjectId()))).collect(Collectors.toList());
  if("project.reconciliation_schedule".equals(code))return schedule(q,w,ps);
  if("finance.material_settled_rent".equals(code)){
   if(!Boolean.TRUE.equals(w.getFinanceEnabled()))throw new MyBizException("未开通财务分析权限","AGT403");
   return materialRent(q,w,ps);
  }
  throw new MyBizException("未知分析能力","AGT400");
 }
 private AgentSkillExecution schedule(JsonNode q,AgentRuntimeRecords.Workspace w,List<ProjectEntity> ps){
  LocalDate today=LocalDate.now(ZoneId.of(timezone));YearMonth month;
  try{month=q.hasNonNull("targetMonth")?YearMonth.parse(q.path("targetMonth").asText()):YearMonth.from(today);}catch(Exception e){throw new MyBizException("目标月份格式应为YYYY-MM","AGT400");}
  if(month.isBefore(YearMonth.from(today)))throw new MyBizException("合同缺少历史生效版本，不能重建过去月份的计划；可查询当前及未来计划","AGT400");
  if(month.isAfter(YearMonth.from(today).plusMonths(24)))throw new MyBizException("计划最多支持未来24个月","AGT400");
  String type=q.path("reconciliationType").asText("FINANCE");
  if(!"FINANCE".equals(type))return output("project.reconciliation_schedule","project.reconciliation_schedule",Collections.emptyList(),map("availability","UNSUPPORTED","completeness","UNKNOWN","reason","物料对账排期规则尚未核实"),"当前仅支持按合同月周期生成财务对账计划，不能以财务排期代替物料对账。");
  List<Map<String,Object>> items=new ArrayList<>();int missing=0;
  for(ProjectEntity p:ps){
   List<ContractEntity> cs=contracts.selectList(new QueryWrapper<ContractEntity>().eq("cid",w.getCid()).eq("project_id",p.getProjectId()).orderByDesc("create_date","contract_id"));
   if(cs.size()!=1||cs.get(0).getReconciliationPeriod()==null||cs.get(0).getReconciliationPeriod()<1){items.add(map("projectId",p.getProjectId(),"projectName",p.getProjectName(),"status","RULE_MISSING","reason",cs.size()>1?"存在多份合同，缺少有效版本选择规则":"缺少有效月周期"));missing++;continue;}
   ContractEntity c=cs.get(0);LocalDate first=null;LocalDate latestRent=null;
   if("rent_in".equalsIgnoreCase(p.getProjectBusinessType()))for(RentInDocumentEntity d:rentIns.selectList(new QueryWrapper<RentInDocumentEntity>().eq("cid",w.getCid()).eq("project_id",p.getProjectId()))){LocalDate date=date(d.getRentInDate());if(date!=null&&!date.isAfter(today)){if(first==null||date.isBefore(first))first=date;if(latestRent==null||date.isAfter(latestRent))latestRent=date;}}
   else for(RentDocumentEntity d:rents.selectList(new QueryWrapper<RentDocumentEntity>().eq("cid",w.getCid()).eq("project_id",p.getProjectId()))){LocalDate date=date(d.getRentDate());if(date!=null&&!date.isAfter(today)){if(first==null||date.isBefore(first))first=date;if(latestRent==null||date.isAfter(latestRent))latestRent=date;}}
   if(first==null){boolean closed="1".equals(p.getProjectStatusFlag());if(closed)missing++;
    items.add(map("projectId",p.getProjectId(),"projectName",p.getProjectName(),"status",closed?"RULE_MISSING":"NO_BUSINESS_ANCHOR","reason",closed?"已完成项目缺少可核验起租及截止依据":"尚无有效起租业务日期"));continue;}
   if(first.isBefore(today.minusYears(100))||c.getReconciliationPeriod()>120){items.add(map("projectId",p.getProjectId(),"status","RULE_MISSING","reason","周期或起租日异常"));missing++;continue;}
   List<SettlementDocumentEntity> completed=settlements.selectList(new QueryWrapper<SettlementDocumentEntity>().eq("cid",w.getCid()).eq("project_id",p.getProjectId()).eq("type","3").eq("status","ACTIVE"));
   // Closed projects use the same last return/return-to-supplier boundary as the settlement domain.
   // Unlike its interactive fallback to today, a missing closing event is not a reliable planned obligation.
   LocalDate closingDate=null;
   if("1".equals(p.getProjectStatusFlag())) {
    boolean invalidClosingEvent=false;
    if("rent_in".equalsIgnoreCase(p.getProjectBusinessType())) {
     for(RentInReturnDocumentEntity d:rentInReturns.selectList(new QueryWrapper<RentInReturnDocumentEntity>().eq("cid",w.getCid()).eq("project_id",p.getProjectId()))) {
      LocalDate day=date(d.getRentInReturnDate());if(day==null||day.isAfter(today)){invalidClosingEvent=true;continue;}
      if(closingDate==null||day.isAfter(closingDate))closingDate=day;
     }
    } else {
     for(ReturnDocumentEntity d:returns.selectList(new QueryWrapper<ReturnDocumentEntity>().eq("cid",w.getCid()).eq("project_id",p.getProjectId()))) {
      LocalDate day=date(d.getReturnDate());if(day==null||day.isAfter(today)){invalidClosingEvent=true;continue;}
      if(closingDate==null||day.isAfter(closingDate))closingDate=day;
     }
    }
    if(closingDate==null||invalidClosingEvent||closingDate.isBefore(first)||(latestRent!=null&&latestRent.isAfter(closingDate))) {
     items.add(map("projectId",p.getProjectId(),"projectName",p.getProjectName(),"status","RULE_MISSING","reason","项目已完成，但缺少可核验的最终归还/退租截止日期，或截止后仍存在起租记录"));missing++;continue;
    }
   }
   LocalDate effectiveEnd=closingDate!=null&&closingDate.isBefore(month.atEndOfMonth())?closingDate:month.atEndOfMonth();
   LocalDate start=first;
   while(!start.isAfter(effectiveEnd)){
    LocalDate end=start.plusMonths(c.getReconciliationPeriod()).minusDays(1);
    if(closingDate!=null&&end.isAfter(closingDate))end=closingDate;
    if(end.isAfter(effectiveEnd))break;
    boolean covered=covered(start,end,completed);
    if(!covered||!end.isBefore(month.atDay(1)))items.add(map("projectId",p.getProjectId(),"projectName",p.getProjectName(),"contractId",c.getContractId(),"cycleMonths",c.getReconciliationPeriod(),"anchorDate",first.toString(),"periodStart",start.toString(),"periodEnd",end.toString(),"plannedDate",end.toString(),"status",covered?"COMPLETED":end.isBefore(month.atDay(1))?"PRIOR_UNCOMPLETED":"PENDING","notYetDue",end.isAfter(today),"closingDate",closingDate==null?null:closingDate.toString(),"closingBasis",closingDate==null?"ONGOING_PROJECT":"LAST_RETURN_DATE_COMPLETED_PROJECT"));
    start=end.plusDays(1);
   }
  }
  return output("project.reconciliation_schedule","project.reconciliation_schedule",items,map("availability",missing>0?"MISSING_DATA":"AVAILABLE","completeness",missing>0?"PARTIAL":"COMPLETE","definitionVersion","1","targetMonth",month.toString(),"dateBasis","FIRST_BUSINESS_DATE_PLUS_CONTRACT_MONTHS","ruleMissingProjects",missing),"按现有首张租出/租入日期和合同自然月周期生成 "+month+" 财务对账计划，找到 "+items.size()+" 项计划/缺口；规则缺失项目 "+missing+" 个。计划日为账期结束日，不代表合同付款到期日。未配置的终止、节假日、合同变更规则不自动推断。");
 }
 static boolean covered(LocalDate start,LocalDate end,List<SettlementDocumentEntity> docs){
  List<SettlementDocumentEntity> valid=docs.stream().filter(d->date(d.getStartDate())!=null&&date(d.getEndDate())!=null).sorted(Comparator.comparing(d->date(d.getStartDate()))).collect(Collectors.toList());LocalDate cursor=start;
  for(SettlementDocumentEntity d:valid){LocalDate a=date(d.getStartDate()),b=date(d.getEndDate());if(a.isAfter(cursor))break;if(!b.isBefore(cursor))cursor=b.plusDays(1);if(cursor.isAfter(end))return true;}return false;
 }
 private AgentSkillExecution materialRent(JsonNode q,AgentRuntimeRecords.Workspace w,List<ProjectEntity> ps){
  LocalDate today=LocalDate.now(ZoneId.of(timezone));LocalDate from=q.hasNonNull("startDate")?date(q.path("startDate").asText()):today.withDayOfYear(1);LocalDate to=q.hasNonNull("endDate")?date(q.path("endDate").asText()):today;
  if(from==null||to==null||from.isAfter(to)||to.isAfter(today))throw new MyBizException("统计日期无效或晚于今天","AGT400");
  List<String> ids=ps.stream().filter(p->!"rent_in".equalsIgnoreCase(p.getProjectBusinessType())).map(ProjectEntity::getProjectId).collect(Collectors.toList());
  List<SettlementDocumentEntity> docs=ids.isEmpty()?Collections.emptyList():settlements.selectList(new QueryWrapper<SettlementDocumentEntity>().eq("cid",w.getCid()).in("project_id",ids).eq("type","3").and(v->v.eq("status","ACTIVE").or().isNull("status")));
  docs=docs.stream().filter(d->date(d.getEndDate())==null||(!date(d.getEndDate()).isBefore(from)&&!date(d.getEndDate()).isAfter(to))).collect(Collectors.toList());
  Map<String,BigDecimal> amounts=new LinkedHashMap<>();Map<String,Map<String,Object>> labels=new LinkedHashMap<>();Map<String,Set<String>> sourceIds=new LinkedHashMap<>();List<String> failures=new ArrayList<>();int covered=0;
  Set<String> seenPeriod=new HashSet<>();
  for(SettlementDocumentEntity d:docs){
   if(!w.getCid().equals(d.getCid())||!ids.contains(d.getProjectId()))throw new MyBizException("结算数据越界","AGT403");
   if(date(d.getEndDate())==null||date(d.getStartDate())==null||date(d.getStartDate()).isAfter(date(d.getEndDate()))){failures.add(d.getSettlementDocumentId()+":账期日期缺失或异常，无法可靠归属期间");continue;}
   if(!"ACTIVE".equals(d.getStatus())){failures.add(d.getSettlementDocumentId()+":历史结算缺少明确有效状态");continue;}
   String period=d.getProjectId()+"|"+d.getStartDate()+"|"+d.getEndDate();
   if(!seenPeriod.add(period)){failures.add(d.getSettlementDocumentId()+":重复有效账期");continue;}
   List<SettlementRentFeeSegmentEntity> fs=segments.selectList(new QueryWrapper<SettlementRentFeeSegmentEntity>().eq("cid",w.getCid()).eq("settlement_document_id",d.getSettlementDocumentId()).eq("project_id",d.getProjectId()));
   BigDecimal expected=null,expectedIncrement=null;
   try{JsonNode summary=json.readTree(d.getSummarySnapshotJson()).path("summary");
    if(summary.path("currentRentFee").isNumber())expected=summary.path("currentRentFee").decimalValue();
    if(summary.path("incrementRentFee").isNumber())expectedIncrement=summary.path("incrementRentFee").decimalValue();
   }catch(Exception ignored){}
   BigDecimal sum=fs.stream().map(SettlementRentFeeSegmentEntity::getMaterialFee).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
   BigDecimal incrementSum=fs.stream().map(SettlementRentFeeSegmentEntity::getIncrementAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add);
   boolean valid=expected!=null&&expectedIncrement!=null&&!fs.isEmpty()
      &&sum.setScale(2,RoundingMode.HALF_UP).compareTo(expected.setScale(2,RoundingMode.HALF_UP))==0
      &&incrementSum.setScale(2,RoundingMode.HALF_UP).compareTo(expectedIncrement.setScale(2,RoundingMode.HALF_UP))==0;
   for(SettlementRentFeeSegmentEntity f:fs)if(!StringUtils.hasText(f.getMaterialId())||f.getMaterialFee()==null||f.getIncrementAmount()==null||!w.getCid().equals(f.getCid())||!d.getProjectId().equals(f.getProjectId())||!d.getSettlementDocumentId().equals(f.getSettlementDocumentId()))valid=false;
   if(!valid){failures.add(d.getSettlementDocumentId()+":分段缺失、材料标识缺失或与正式快照的基础/递增租金不一致");continue;}
   covered++;
   for(SettlementRentFeeSegmentEntity f:fs){String key=f.getMaterialId()+"|"+f.getMaterialSpecification();amounts.merge(key,f.getMaterialFee().add(f.getIncrementAmount()),BigDecimal::add);sourceIds.computeIfAbsent(key,k->new LinkedHashSet<>()).add(d.getSettlementDocumentId());labels.put(key,map("materialId",f.getMaterialId(),"materialName",f.getMaterialName(),"specification",f.getMaterialSpecification()));}
  }
  boolean complete=failures.isEmpty();List<Map<String,Object>> rows=new ArrayList<>();
  if(complete){amounts.entrySet().stream().sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).forEach(e->{Map<String,Object> row=labels.get(e.getKey());row.put("settledRentAmount",e.getValue().setScale(2,RoundingMode.HALF_UP));row.put("currency","CNY");row.put("sourceSettlementIds",sourceIds.get(e.getKey()));rows.add(row);});}
  Map<String,Object> criteria=map("availability",complete?"AVAILABLE":"MISSING_DATA","completeness",complete?"COMPLETE":"PARTIAL","metricId","material.settled_rent_by_settlement_end","definitionVersion","2","dateBasis","SETTLEMENT_END_DATE","startDate",from.toString(),"endDate",to.toString(),"currency","CNY","coverageNumerator",covered,"coverageDenominator",docs.size(),"coverageDenominatorDefinition","当前有效租出财务结算单中账期结束日在查询范围内的单据数","includesIncrementRent",true,"amountBasis","MATERIAL_FEE_PLUS_INCREMENT_AMOUNT","settlementLevelAdjustmentsAllocated",false,"globalRankingAllowed",complete,"excludedDocuments",failures,"historicalStateReconstruction",false);
  return output("finance.material_settled_rent","material.settled_rent_by_settlement_end",rows,criteria,complete?"按结算账期结束日归属，"+from+" 至 "+to+" 的已结算材料租金（基础租金＋递增租金）已分别核对 "+covered+" 张正式结算快照。仅表示当前有效结算分段的基础与递增租金，未向材料分摊结算单级调整，不表示利润、回款或未结算租金。":"已结算材料租金数据覆盖或金额核对不完整（"+covered+"/"+docs.size()+" 张），本次不输出全量排名，避免把部分历史当作最高收入结论。");
 }
 private AgentSkillExecution output(String code,String metric,List<Map<String,Object>> rows,Map<String,Object> criteria,String answer){
  AgentEvidence e=new AgentEvidence();e.setToolCode(code);e.setCriteria(criteria);e.setRecordCount(rows.size());
  e.setMetricId(metric);e.setDefinitionVersion(String.valueOf(criteria.getOrDefault("definitionVersion","1")));
  e.setAvailability(String.valueOf(criteria.getOrDefault("availability","UNKNOWN")));e.setCompleteness(String.valueOf(criteria.getOrDefault("completeness","UNKNOWN")));
  e.setTimeBasis(String.valueOf(criteria.getOrDefault("dateBasis","UNSPECIFIED")));e.setQueriedAt(java.time.OffsetDateTime.now().toString());
  AgentSkillExecution x=new AgentSkillExecution();x.setIntent(code.replace('.','_'));x.setConfidence(1d);x.setAnswer(answer);x.setEvidence(e);x.setCards(Collections.singletonList(map("type","business-query-result","schemaVersion","1","metricId",metric,"items",rows,"totalCount",rows.size(),"displayedCount",rows.size(),"criteria",criteria)));return x;
 }
 private static LocalDate date(String s){try{return LocalDate.parse(s);}catch(Exception e){return null;}}
 private static Map<String,Object> map(Object... a){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<a.length;i+=2)m.put(a[i].toString(),a[i+1]);return m;}
}
