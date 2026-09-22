package com.zjyz.agent.workspace.tool;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zjyz.dao.*;
import com.zjyz.pojo.entity.*;
import com.zjyz.common.exception.MyBizException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.*;
/** Shared physical balance facts. Compensation/payment obligations are deliberately separate metrics. */
@Service
public class MaterialBalanceService {
 private final RentDocumentMapper rentDocs; private final RentDocumentMaterialMapper rentLines;
 private final ReturnDocumentMapper returnsDocs; private final ReturnDocumentMaterialMapper returnsLines;
 private final RentInDocumentMapper rentInDocs; private final RentInDocumentMaterialMapper rentInLines;
 private final RentInReturnDocumentMapper rentInReturnDocs; private final RentInReturnDocumentMaterialMapper rentInReturnLines;
 public MaterialBalanceService(RentDocumentMapper rentDocs, RentDocumentMaterialMapper rentLines, ReturnDocumentMapper returnsDocs, ReturnDocumentMaterialMapper returnsLines, RentInDocumentMapper rentInDocs, RentInDocumentMaterialMapper rentInLines, RentInReturnDocumentMapper rentInReturnDocs, RentInReturnDocumentMaterialMapper rentInReturnLines) {
this.rentDocs=rentDocs; this.rentLines=rentLines;
this.returnsDocs=returnsDocs; this.returnsLines=returnsLines;
this.rentInDocs=rentInDocs; this.rentInLines=rentInLines;
this.rentInReturnDocs=rentInReturnDocs; this.rentInReturnLines=rentInReturnLines;
 }
 public Result query(String cid, Map<String,ProjectEntity> projects, LocalDate asOf) {
 if(!StringUtils.hasText(cid)||asOf==null) throw new MyBizException("材料事实上下文缺失","AGT400");
 Result result=new Result(); if(projects.isEmpty()) return result;
 List<String> ids=new ArrayList<>(projects.keySet());
 Set<String> rentIds=new HashSet<>();
 for(RentDocumentEntity d:rentDocs.selectList(new QueryWrapper<RentDocumentEntity>().eq("cid",cid).in("project_id",ids))) {
 if(!cid.equals(d.getCid())||!projects.containsKey(d.getProjectId())) continue;
 LocalDate day=date(d.getRentDate());
 if(Integer.valueOf(1).equals(d.getReviewStatus())&&day!=null&&!day.isAfter(asOf)) rentIds.add(d.getRentDocumentId()); else result.excludedDocuments++;
 }
 for(RentDocumentMaterialEntity line:rentLines.selectList(new QueryWrapper<RentDocumentMaterialEntity>().eq("cid",cid).in("project_id",ids))) {
 if(!cid.equals(line.getCid())||!projects.containsKey(line.getProjectId())||!rentIds.contains(line.getDocumentId())) continue;
 if(false != "rent_in".equalsIgnoreCase(projects.get(line.getProjectId()).getProjectBusinessType())) continue;
 if("BUYOUT".equalsIgnoreCase(line.getMaterialBusinessType())) continue;
 String unit=StringUtils.hasText(line.getCountingUnit())?line.getCountingUnit():line.getMaterialUnit();
 unit=StringUtils.hasText(unit)?unit.trim():null;
 String key=line.getProjectId()+"\u0000"+line.getMaterialId()+"\u0000"+line.getMaterialSpecification()+"\u0000"+unit;
 Balance b=result.balances.computeIfAbsent(key,k->new Balance());
 b.projectId=line.getProjectId(); b.materialId=line.getMaterialId();b.name=line.getMaterialName();b.specification=line.getMaterialSpecification(); b.unit=StringUtils.hasText(unit)?unit.trim():"未填写单位"; b.unitMissing=!StringUtils.hasText(unit);
 if((line.getCountingQuantity()==null&&line.getMaterialNumber()==null)||(line.getCountingQuantity()!=null&&!Double.isFinite(line.getCountingQuantity()))) throw new MyBizException("材料数量缺失或非法，无法形成可靠余额","AGT_DATA_INTEGRITY");
 BigDecimal q=line.getCountingQuantity()!=null?BigDecimal.valueOf(line.getCountingQuantity()):BigDecimal.valueOf(line.getMaterialNumber()==null?0:line.getMaterialNumber());
 b.rented=b.rented.add(q);
 b.sourceDocumentIds.add(line.getDocumentId());
 result.sourceRows++;
 }
 Set<String> returnsIds=new HashSet<>();
 for(ReturnDocumentEntity d:returnsDocs.selectList(new QueryWrapper<ReturnDocumentEntity>().eq("cid",cid).in("project_id",ids))) {
 if(!cid.equals(d.getCid())||!projects.containsKey(d.getProjectId())) continue;
 LocalDate day=date(d.getReturnDate());
 if(Integer.valueOf(1).equals(d.getReviewStatus())&&day!=null&&!day.isAfter(asOf)) returnsIds.add(d.getReturnDocumentId()); else result.excludedDocuments++;
 }
 for(ReturnDocumentMaterialEntity line:returnsLines.selectList(new QueryWrapper<ReturnDocumentMaterialEntity>().eq("cid",cid).in("project_id",ids))) {
 if(!cid.equals(line.getCid())||!projects.containsKey(line.getProjectId())||!returnsIds.contains(line.getDocumentId())) continue;
 if(false != "rent_in".equalsIgnoreCase(projects.get(line.getProjectId()).getProjectBusinessType())) continue;
 if("BUYOUT".equalsIgnoreCase(line.getMaterialBusinessType())) continue;
 String unit=StringUtils.hasText(line.getCountingUnit())?line.getCountingUnit():line.getMaterialUnit();
 unit=StringUtils.hasText(unit)?unit.trim():null;
 String key=line.getProjectId()+"\u0000"+line.getMaterialId()+"\u0000"+line.getMaterialSpecification()+"\u0000"+unit;
 Balance b=result.balances.computeIfAbsent(key,k->new Balance());
 b.projectId=line.getProjectId(); b.materialId=line.getMaterialId();b.name=line.getMaterialName();b.specification=line.getMaterialSpecification(); b.unit=StringUtils.hasText(unit)?unit.trim():"未填写单位"; b.unitMissing=!StringUtils.hasText(unit);
 if((line.getCountingQuantity()==null&&line.getMaterialNumber()==null)||(line.getCountingQuantity()!=null&&!Double.isFinite(line.getCountingQuantity()))) throw new MyBizException("材料数量缺失或非法，无法形成可靠余额","AGT_DATA_INTEGRITY");
 BigDecimal q=line.getCountingQuantity()!=null?BigDecimal.valueOf(line.getCountingQuantity()):BigDecimal.valueOf(line.getMaterialNumber()==null?0:line.getMaterialNumber());
 b.returned=b.returned.add(q);
 b.sourceDocumentIds.add(line.getDocumentId());
 result.sourceRows++;
 }
 Set<String> rentInIds=new HashSet<>();
 for(RentInDocumentEntity d:rentInDocs.selectList(new QueryWrapper<RentInDocumentEntity>().eq("cid",cid).in("project_id",ids))) {
 if(!cid.equals(d.getCid())||!projects.containsKey(d.getProjectId())) continue;
 LocalDate day=date(d.getRentInDate());
 if(Integer.valueOf(1).equals(d.getReviewStatus())&&day!=null&&!day.isAfter(asOf)) rentInIds.add(d.getRentInDocumentId()); else result.excludedDocuments++;
 }
 for(RentInDocumentMaterialEntity line:rentInLines.selectList(new QueryWrapper<RentInDocumentMaterialEntity>().eq("cid",cid).in("project_id",ids))) {
 if(!cid.equals(line.getCid())||!projects.containsKey(line.getProjectId())||!rentInIds.contains(line.getDocumentId())) continue;
 if(true != "rent_in".equalsIgnoreCase(projects.get(line.getProjectId()).getProjectBusinessType())) continue;
 String unit=StringUtils.hasText(line.getCountingUnit())?line.getCountingUnit():line.getMaterialUnit();
 unit=StringUtils.hasText(unit)?unit.trim():null;
 String key=line.getProjectId()+"\u0000"+line.getMaterialId()+"\u0000"+line.getMaterialSpecification()+"\u0000"+unit;
 Balance b=result.balances.computeIfAbsent(key,k->new Balance());
 b.projectId=line.getProjectId(); b.materialId=line.getMaterialId();b.name=line.getMaterialName();b.specification=line.getMaterialSpecification(); b.unit=StringUtils.hasText(unit)?unit.trim():"未填写单位"; b.unitMissing=!StringUtils.hasText(unit);
 if((line.getCountingQuantity()==null&&line.getMaterialNumber()==null)||(line.getCountingQuantity()!=null&&!Double.isFinite(line.getCountingQuantity()))) throw new MyBizException("材料数量缺失或非法，无法形成可靠余额","AGT_DATA_INTEGRITY");
 BigDecimal q=line.getCountingQuantity()!=null?BigDecimal.valueOf(line.getCountingQuantity()):BigDecimal.valueOf(line.getMaterialNumber()==null?0:line.getMaterialNumber());
 b.rented=b.rented.add(q);
 b.sourceDocumentIds.add(line.getDocumentId());
 result.sourceRows++;
 }
 Set<String> rentInReturnIds=new HashSet<>();
 for(RentInReturnDocumentEntity d:rentInReturnDocs.selectList(new QueryWrapper<RentInReturnDocumentEntity>().eq("cid",cid).in("project_id",ids))) {
 if(!cid.equals(d.getCid())||!projects.containsKey(d.getProjectId())) continue;
 LocalDate day=date(d.getRentInReturnDate());
 if(Integer.valueOf(1).equals(d.getReviewStatus())&&day!=null&&!day.isAfter(asOf)) rentInReturnIds.add(d.getRentInReturnDocumentId()); else result.excludedDocuments++;
 }
 for(RentInReturnDocumentMaterialEntity line:rentInReturnLines.selectList(new QueryWrapper<RentInReturnDocumentMaterialEntity>().eq("cid",cid).in("project_id",ids))) {
 if(!cid.equals(line.getCid())||!projects.containsKey(line.getProjectId())||!rentInReturnIds.contains(line.getDocumentId())) continue;
 if(true != "rent_in".equalsIgnoreCase(projects.get(line.getProjectId()).getProjectBusinessType())) continue;
 String unit=StringUtils.hasText(line.getCountingUnit())?line.getCountingUnit():line.getMaterialUnit();
 unit=StringUtils.hasText(unit)?unit.trim():null;
 String key=line.getProjectId()+"\u0000"+line.getMaterialId()+"\u0000"+line.getMaterialSpecification()+"\u0000"+unit;
 Balance b=result.balances.computeIfAbsent(key,k->new Balance());
 b.projectId=line.getProjectId(); b.materialId=line.getMaterialId();b.name=line.getMaterialName();b.specification=line.getMaterialSpecification(); b.unit=StringUtils.hasText(unit)?unit.trim():"未填写单位"; b.unitMissing=!StringUtils.hasText(unit);
 if((line.getCountingQuantity()==null&&line.getMaterialNumber()==null)||(line.getCountingQuantity()!=null&&!Double.isFinite(line.getCountingQuantity()))) throw new MyBizException("材料数量缺失或非法，无法形成可靠余额","AGT_DATA_INTEGRITY");
 BigDecimal q=line.getCountingQuantity()!=null?BigDecimal.valueOf(line.getCountingQuantity()):BigDecimal.valueOf(line.getMaterialNumber()==null?0:line.getMaterialNumber());
 b.returned=b.returned.add(q);
 b.sourceDocumentIds.add(line.getDocumentId());
 result.sourceRows++;
 }
 return result;
 }
 private static LocalDate date(String s){try{return LocalDate.parse(s);}catch(Exception e){return null;}}
 public static class Result { public final Map<String,Balance> balances=new LinkedHashMap<>(); public int excludedDocuments;public int sourceRows; }
 public static class Balance {
 public String projectId,materialId,name,specification,unit;
 public boolean unitMissing;
 public final Set<String> sourceDocumentIds=new LinkedHashSet<>();
 public BigDecimal rented=BigDecimal.ZERO,returned=BigDecimal.ZERO;
 public BigDecimal outstanding(){return rented.subtract(returned);}
 }
}
