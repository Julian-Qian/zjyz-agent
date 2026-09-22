package com.zjyz.agent.workspace.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zjyz.agent.workspace.attachment.AgentAttachmentRecords.*;

class AgentContractReviewServiceTest {
    ObjectMapper json=new ObjectMapper();AgentAttachmentService attachments=mock(AgentAttachmentService.class);AgentV2ModelGateway gateway=mock(AgentV2ModelGateway.class);
    AgentContractReviewService service=new AgentContractReviewService(attachments,gateway,json);
    @Test void rejectsInventedQuoteAndSingleSourceContradiction()throws Exception{
        Map<String,Object> source=source("b1","付款期限为签收后三十日内支付全部租金。");
        Map<String,Object> finding=finding("a:1:b1","这是根本不存在的一段原文引用。");
        assertNull(service.validateFinding(json.valueToTree(finding),List.of(source),true,1));
        finding=finding("a:1:b1",String.valueOf(source.get("text")));finding.put("issueType","CONTRADICTION");assertNull(service.validateFinding(json.valueToTree(finding),List.of(source),true,1));
    }
    @Test void contradictionRequiresTwoExactSourceQuotes(){
        Map<String,Object> one=source("b1","付款期限为签收后三十日内支付全部租金。"),two=source("b2","付款期限为签收后六十日内支付全部租金。");
        Map<String,Object> f=finding("a:1:b1",String.valueOf(one.get("text")));f.put("issueType","CONTRADICTION");f.put("relatedQuotes",List.of(Map.of("sourceId","a:1:b2","originalExcerpt",two.get("text"))));
        Map<String,Object> result=service.validateFinding(json.valueToTree(f),List.of(one,two),false,1);assertNotNull(result);assertEquals(2,((List<?>)result.get("sourceRefs")).size());
    }
    @Test void successfulSingleDocumentRetainsCitationsAndAccountsActualCall()throws Exception{
        ParsedDocument doc=document("付款期限为签收后三十日内支付全部租金。");Map<String,Object> ref=Map.of("attachmentId","a","parseRevision",1);
        when(attachments.readFrozen("t",ref)).thenReturn(doc);when(gateway.isAvailable()).thenReturn(true);
        when(gateway.complete(anyList(),anyList(),anyString())).thenReturn(result(json.writeValueAsString(Map.of("findings",List.of(finding("a:1:b1",doc.getBlocks().get(0).getText()))))));
        AtomicInteger before=new AtomicInteger(),after=new AtomicInteger();Map<String,Object> output=service.review("t",List.of(ref),"审阅",before::incrementAndGet,m->after.incrementAndGet());
        assertEquals("FULL",output.get("completionStatus"));assertEquals(1,before.get());assertEquals(1,after.get());verify(attachments,atLeast(2)).validateFrozen("t",List.of(ref));
    }
    @Test void parseGapNeverBecomesFullReview()throws Exception{
        ParsedDocument doc=document("付款期限为签收后三十日内支付全部租金。");doc.gap("第二页无法识别");Map<String,Object>ref=Map.of("attachmentId","a","parseRevision",1);
        when(attachments.readFrozen("t",ref)).thenReturn(doc);when(gateway.isAvailable()).thenReturn(true);when(gateway.complete(anyList(),anyList(),anyString())).thenReturn(result("{\"findings\":[]}"));
        assertEquals("PARTIAL",service.review("t",List.of(ref),"审阅",()->{},m->{}).get("completionStatus"));
    }
    @Test void longDocumentRunsCrossBatchReviewInsteadOfSilentlyTruncating()throws Exception{
        ParsedDocument doc=document("第一段租赁合同付款条件为三十日内支付。".repeat(500));Block second=new Block();second.setId("b2");second.setText("第二段租赁合同付款条件为六十日内支付。".repeat(500));second.setLocation("正文2");doc.getBlocks().add(second);
        Map<String,Object> ref=Map.of("attachmentId","a","parseRevision",1);when(attachments.readFrozen("t",ref)).thenReturn(doc);when(gateway.isAvailable()).thenReturn(true);
        when(gateway.complete(anyList(),anyList(),anyString())).thenReturn(
            result(json.writeValueAsString(Map.of("findings",List.of(),"clauses",List.of(Map.of("sourceId","a:1:b1","originalExcerpt","第一段租赁合同付款条件为三十日内支付。","category","付款","summary","三十日"))))),
            result(json.writeValueAsString(Map.of("findings",List.of(),"clauses",List.of(Map.of("sourceId","a:1:b2","originalExcerpt","第二段租赁合同付款条件为六十日内支付。","category","付款","summary","六十日"))))),result("{\"findings\":[]}"));
        Map<String,Object> result=service.review("t",List.of(ref),"审阅",()->{},m->{});assertEquals("FULL",result.get("completionStatus"));assertEquals(true,result.get("crossBatchChecked"));verify(gateway,times(3)).complete(anyList(),anyList(),anyString());
    }
    @Test void crossBatchQuotaExhaustionRetainsValidatedFindings()throws Exception {
        ParsedDocument doc=document("第一段租赁合同付款条件为三十日内支付。".repeat(500));
        Block second=new Block();second.setId("b2");second.setText("第二段租赁合同付款条件为六十日内支付。".repeat(500));second.setLocation("正文2");doc.getBlocks().add(second);
        Map<String,Object> ref=Map.of("attachmentId","a","parseRevision",1);
        when(attachments.readFrozen("t",ref)).thenReturn(doc);when(gateway.isAvailable()).thenReturn(true);
        String firstQuote="第一段租赁合同付款条件为三十日内支付。",secondQuote="第二段租赁合同付款条件为六十日内支付。";
        when(gateway.complete(anyList(),anyList(),anyString())).thenReturn(
            result(json.writeValueAsString(Map.of("findings",List.of(finding("a:1:b1",firstQuote)),"clauses",List.of(Map.of("sourceId","a:1:b1","originalExcerpt",firstQuote,"category","付款","summary","三十日"))))),
            result(json.writeValueAsString(Map.of("findings",List.of(finding("a:1:b2",secondQuote)),"clauses",List.of(Map.of("sourceId","a:1:b2","originalExcerpt",secondQuote,"category","付款","summary","六十日"))))));
        AtomicInteger attempts=new AtomicInteger(),recorded=new AtomicInteger();
        Map<String,Object> output=service.review("t",List.of(ref),"审阅",()->{
            if(attempts.incrementAndGet()==3)throw new com.zjyz.common.exception.MyBizException("额度不足","AGT429");
        },m->recorded.incrementAndGet());
        assertEquals("PARTIAL",output.get("completionStatus"));
        assertEquals(false,output.get("crossBatchChecked"));
        assertEquals(2,((List<?>)output.get("findings")).size());
        assertEquals(2,output.get("reviewedBatches"));assertEquals(2,recorded.get());
        assertTrue(((List<?>)output.get("warnings")).stream().anyMatch(v->v.toString().contains("额度不足")));
        verify(gateway,times(2)).complete(anyList(),anyList(),anyString());
        verify(attachments,atLeastOnce()).validateFrozen("t",List.of(ref));
    }
    private Map<String,Object> source(String id,String text){Map<String,Object>s=new LinkedHashMap<>();s.put("sourceId","a:1:"+id);s.put("attachmentId","a");s.put("parseRevision",1);s.put("blockId",id);s.put("location","正文");s.put("text",text);return s;}
    private Map<String,Object> finding(String source,String quote){Map<String,Object>f=new LinkedHashMap<>();f.put("sourceId",source);f.put("originalExcerpt",quote);f.put("issueType","AMBIGUOUS");f.put("severity","MEDIUM");f.put("problem","签收日需要明确");f.put("suggestion","明确双方确认的签收日依据");return f;}
    private ParsedDocument document(String text){ParsedDocument d=new ParsedDocument();Block b=new Block();b.setId("b1");b.setText(text);b.setLocation("正文1");d.getBlocks().add(b);return d;}
    private AgentModelGateway.ModelResult result(String text){AgentModelGateway.ModelResult r=new AgentModelGateway.ModelResult();r.setSuccess(true);r.setContent(text);r.setFinishReason("stop");return r;}
}
