package com.zjyz.agent.workspace.tool;
import com.zjyz.dao.*;import com.zjyz.pojo.entity.*;import org.junit.jupiter.api.Test;import java.time.LocalDate;import java.util.*;import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.any;
class MaterialBalanceServiceTest {
 @Test void filtersUnreviewedFutureAndForeignRowsAndPreservesFractionalQuantity(){
  RentDocumentMapper rd=mock(RentDocumentMapper.class);RentDocumentMaterialMapper rm=mock(RentDocumentMaterialMapper.class);
  MaterialBalanceService s=new MaterialBalanceService(rd,rm,mock(ReturnDocumentMapper.class),mock(ReturnDocumentMaterialMapper.class),mock(RentInDocumentMapper.class),mock(RentInDocumentMaterialMapper.class),mock(RentInReturnDocumentMapper.class),mock(RentInReturnDocumentMaterialMapper.class));
  List<RentDocumentEntity> docs=new ArrayList<>();List<RentDocumentMaterialEntity> lines=new ArrayList<>();
  for(int i=0;i<4;i++){RentDocumentEntity d=new RentDocumentEntity();d.setCid(i==3?"foreign":"c");d.setProjectId("p");d.setRentDocumentId("d"+i);d.setReviewStatus(i==1?0:1);d.setRentDate(i==2?"2030-01-01":"2026-01-01");docs.add(d);RentDocumentMaterialEntity l=new RentDocumentMaterialEntity();l.setCid(d.getCid());l.setProjectId("p");l.setDocumentId("d"+i);l.setMaterialId("m");l.setCountingUnit("米");l.setCountingQuantity(1.25);lines.add(l);}
  when(rd.selectList(any())).thenReturn(docs);when(rm.selectList(any())).thenReturn(lines);ProjectEntity p=new ProjectEntity();p.setProjectBusinessType("rent_out");
  MaterialBalanceService.Result result=s.query("c",Collections.singletonMap("p",p),LocalDate.of(2026,9,19));
  assertEquals(1,result.sourceRows);assertEquals(2,result.excludedDocuments);assertEquals(new BigDecimal("1.25"),result.balances.values().iterator().next().outstanding());
 }
}
