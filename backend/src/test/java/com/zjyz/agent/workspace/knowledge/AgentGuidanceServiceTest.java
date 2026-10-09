package com.zjyz.agent.workspace.knowledge;
import com.zjyz.service.HelpCenterService;
import com.zjyz.pojo.param.ret.HelpArticleBriefRet;
import com.zjyz.pojo.param.ret.HelpArticleDetailRet;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.orch.AgentSkillExecution;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentGuidanceServiceTest {
    final HelpCenterService help=mock(HelpCenterService.class);
    final AgentV2ModelGateway gateway=mock(AgentV2ModelGateway.class);
    final AgentGuidanceService service=new AgentGuidanceService(help);
    final String source="进入目标租出项目，打开归还单列表，核对材料名称、规格、数量与日期。";
    AgentGuidanceServiceTest() {
        ReflectionTestUtils.setField(service,"gateway",gateway);when(gateway.isAvailable()).thenReturn(true);
        HelpArticleBriefRet brief=new HelpArticleBriefRet();brief.setArticleId(1L);brief.setArticleTitle("查看材料归还记录");
        when(help.queryArticleList(null,null,"all","all")).thenReturn(Collections.singletonList(brief));
        when(help.queryArticleDetail(1L)).thenReturn(detail(source));
    }
    HelpArticleDetailRet detail(String text) {HelpArticleDetailRet d=new HelpArticleDetailRet();d.setArticleId(1L);d.setArticleTitle("查看材料归还记录");d.setArticleContent(text);return d;}
    AgentModelGateway.ModelResult response(String content){AgentModelGateway.ModelResult r=new AgentModelGateway.ModelResult();r.setSuccess(true);r.setContent(content);return r;}
    void responses(String checked) {when(gateway.interpret(anyList())).thenReturn(response("{\"requestedAction\":\"查看归还记录\",\"articleIds\":[\"1\"]}"),response(checked));}
    @Test void helpStorageFailureDoesNotDisableBasicProductKnowledge() {
        when(help.queryArticleList(null,null,"all","all")).thenThrow(new IllegalStateException("storage unavailable"));
        when(gateway.interpret(anyList())).thenReturn(response("{\"articleIds\":[]}"));
        service.answer("介绍软件",null,()->{},x->{});
        org.mockito.ArgumentCaptor<List> messages=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(gateway).interpret(messages.capture());
        assertTrue(messages.getValue().toString().contains("product:overview"));
    }
    @Test void onboardingUsesReleaseKnowledgeEvenWhenHelpCenterIsEmpty() throws Exception {
        when(help.queryArticleList(null,null,"all","all")).thenReturn(Collections.emptyList());
        String content=service.productOverview().getArticleContent();
        String checked=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("supported",true,"passageIds",AgentGuidanceService.sourcePassages("product:overview",content).stream().map(p -> p.get("passageId")).collect(java.util.stream.Collectors.toList())));
        when(gateway.interpret(anyList())).thenReturn(
            response("```json\n{\"requestedAction\":\"新手入门\",\"articleIds\":[\"product:overview\"]}\n```"),response(checked));
        AgentSkillExecution result=service.answer("我是新用户，请介绍软件和学习顺序",null,()->{},x->{});
        assertNotNull(result.getEvidence());assertEquals("COMPLETE",result.getEvidence().getCompleteness());
        assertTrue(result.getAnswer().contains("建立项目"));assertTrue(result.getAnswer().contains("租出"));assertTrue(result.getAnswer().contains("来源：产品概览"));
        verify(help,never()).queryArticleDetail(any());
    }
    @Test void overviewCannotBeUsedAsUnsupportedOperationInstructions() {
        when(help.queryArticleList(null,null,"all","all")).thenReturn(Collections.emptyList());
        when(gateway.interpret(anyList())).thenReturn(
            response("{\"requestedAction\":\"删除全部数据\",\"articleIds\":[\"product:overview\"]}"),response("{\"supported\":false,\"quotes\":[]}"));
        assertNull(service.answer("如何删除全部企业数据",null,()->{},x->{}).getEvidence());
    }
    @Test void paraphraseUsesSemanticCandidatesAndMeteredCalls() {
        responses("{\"supported\":true,\"passageIds\":[\"1:p1\"]}");
        AtomicInteger before=new AtomicInteger(),after=new AtomicInteger();
        AgentSkillExecution r=service.answer("客户把东西送回来了，我在什么地方看记录？",null,before::incrementAndGet,x->after.incrementAndGet());
        assertNotNull(r.getEvidence());assertEquals("COMPLETE",r.getEvidence().getCompleteness());
        assertTrue(r.getAnswer().contains(source));assertEquals(2,before.get());assertEquals(2,after.get());
        verify(gateway,times(2)).interpret(anyList());
        verify(gateway,never()).complete(anyList(),anyList(),anyString());
    }
    @Test void viewArticleCannotCompleteDeleteQuestion() {
        responses("{\"supported\":false,\"quotes\":[]}");
        AgentSkillExecution r=service.answer("怎么删除归还单",null,()->{},x->{});
        assertNull(r.getEvidence());assertTrue(r.getWarnings().contains("GUIDANCE_ACTION_NOT_COVERED"));
    }
    @Test void inventedPassageNeverCompletes() {
        responses("{\"supported\":true,\"passageIds\":[\"1:p999\"]}");
        assertNull(service.answer("删除归还单",null,()->{},x->{}).getEvidence());
    }
    @Test void changedOrWithdrawnSourceCannotBeCited() {
        responses("{\"supported\":true,\"passageIds\":[\"1:p1\"]}");
        when(help.queryArticleDetail(1L)).thenReturn(detail(source),detail("新的文章正文不再包含原有步骤"));
        assertTrue(service.answer("归还记录在哪里",null,()->{},x->{}).getWarnings().contains("GUIDANCE_SOURCE_CHANGED"));
    }
    @Test void oversizedSourceIsNotTruncatedAndCertifiedComplete() {
        when(help.queryArticleDetail(1L)).thenReturn(detail(String.join("",Collections.nCopies(30000,"字"))));
        responses("{\"supported\":true,\"quotes\":[]}");
        assertTrue(service.answer("归还记录在哪里",null,()->{},x->{}).getWarnings().contains("GUIDANCE_SOURCE_BUDGET"));
        verify(gateway,times(1)).interpret(anyList());
    }
    @Test void unmeteredCallerDoesNotCallModel() {
        assertNull(service.answer("归还记录在哪里",null).getEvidence());verify(gateway,never()).interpret(anyList());
    }
    @Test void managedVersionMustMatchConfiguredDeployment() {
        HelpArticleBriefRet brief=new HelpArticleBriefRet();brief.setTagList(Collections.singletonList("product-version:1.0.36"));
        assertFalse(service.versionApplies(brief));ReflectionTestUtils.setField(service,"productVersion","1.0.36");assertTrue(service.versionApplies(brief));
        ReflectionTestUtils.setField(service,"productVersion","1.0.37");assertFalse(service.versionApplies(brief));
    }
    @Test void exhaustedQuotaPreservesCallerResultsByReturningUnavailable() {
        AgentSkillExecution r=service.answer("怎么查看归还记录",null,
            ()->{throw new com.zjyz.common.exception.MyBizException("额度不足","AGT429");},x->{});
        assertNull(r.getEvidence());assertTrue(r.getWarnings().contains("GUIDANCE_BUDGET_EXCEEDED"));
        verify(gateway,never()).interpret(anyList());
    }
    @Test void rendersExactSelectedSourceOnceIgnoringModelRewrites() {
        responses("{\"supported\":true,\"passageIds\":[\"1:p1\",\"1:p1\"],\"text\":\"虚构操作\"}");
        AgentSkillExecution result=service.answer("归还记录在哪里",null,()->{},x->{});
        assertTrue(result.getAnswer().contains(source));
        assertEquals(result.getAnswer().indexOf(source),result.getAnswer().lastIndexOf(source));
        assertFalse(result.getAnswer().contains("虚构操作"));
    }
    @Test void paragraphIdsPreserveStepsAndConditionsWithoutReproduction() {
        String conditions="只有具有项目权限的账号可以操作。";
        String steps="1. 打开租出管理。\n2. 点击新建，填写项目名称和我方负责人。";
        when(help.queryArticleDetail(1L)).thenReturn(detail(conditions+"\n\n"+steps));
        responses("{\"supported\":true,\"passageIds\":[\"1:p1\",\"1:p2\"]}");
        AgentSkillExecution result=service.answer("怎么新建租出项目",null,()->{},x->{});
        assertNotNull(result.getEvidence()); assertTrue(result.getAnswer().contains(conditions));
        assertTrue(result.getAnswer().contains(steps));
    }

    @Test void headingIsNeverIndependentEvidenceAndExplanationKeepsItsContext() {
        List<Map<String,String>> passages=AgentGuidanceService.sourcePassages("1",
            "# 对账\n\n## 全额原价\n\n按约定单价计算。\n\n仍应用最小日数。\n\n## 只有标题");
        assertEquals(2,passages.size());
        assertEquals("## 全额原价\n\n按约定单价计算。",passages.get(0).get("text"));
        assertTrue(passages.get(1).get("heading").contains("全额原价"));
        assertFalse(passages.stream().anyMatch(p -> p.get("text").equals("## 只有标题")));
    }
    @Test void selectedOptionIncludesMeaningRatherThanJustHeading() {
        when(help.queryArticleDetail(1L)).thenReturn(detail("### 版式\n\n只调整打印布局，不改变金额。"));
        responses("{\"supported\":true,\"passageIds\":[\"1:p1\"]}");
        assertTrue(service.answer("版式是什么意思",null,()->{},x->{}).getAnswer().contains("不改变金额"));
    }
    @Test void skippedHeadingLevelsDoNotRetainPreviousSibling() {
        List<Map<String,String>> p=AgentGuidanceService.sourcePassages("1","# 模块\n\n### 开启\n\n开启解释。\n\n### 关闭\n\n关闭解释。");
        assertEquals("# 模块 > ### 关闭",p.get(1).get("heading"));
        assertFalse(p.get(1).get("heading").contains("开启"));
    }
    @Test void largeCatalogSearchesLaterBatchAndMetersEveryCall() throws Exception {
        List<HelpArticleBriefRet> catalog=new ArrayList<>();
        for(long id=1;id<=85;id++) {
            HelpArticleBriefRet brief=new HelpArticleBriefRet();brief.setArticleId(id);brief.setArticleTitle("模块"+id);
            brief.setArticleSummary(String.join("",Collections.nCopies(500,"字")));catalog.add(brief);
        }
        when(help.queryArticleList(null,null,"all","all")).thenReturn(catalog);
        HelpArticleDetailRet last=detail(source);last.setArticleId(85L);when(help.queryArticleDetail(85L)).thenReturn(last);
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        when(gateway.interpret(anyList())).thenAnswer(invocation -> {
            List<Map<String,Object>> messages=invocation.getArgument(0);
            com.fasterxml.jackson.databind.JsonNode data=json.readTree(messages.get(1).get("content").toString().substring(5));
            if(data.has("documents"))return response("{\"supported\":true,\"passageIds\":[\"85:p1\"]}");
            boolean found=false;for(com.fasterxml.jackson.databind.JsonNode item:data.path("catalog"))if("85".equals(item.path("articleId").asText()))found=true;
            return response(found?"{\"articleIds\":[\"85\"]}":"{\"articleIds\":[]}");
        });
        AtomicInteger before=new AtomicInteger(),after=new AtomicInteger();
        AgentSkillExecution result=service.answer("需要末尾模块的说明",null,before::incrementAndGet,r->after.incrementAndGet());
        assertNotNull(result.getEvidence());assertTrue(result.getAnswer().contains(source));
        assertEquals(4,before.get());assertEquals(4,after.get());
    }
    @Test void sameFactInModuleAndOptionArticleIsPresentedOnce() {
        HelpArticleBriefRet one=new HelpArticleBriefRet(),two=new HelpArticleBriefRet();one.setArticleId(1L);two.setArticleId(2L);
        when(help.queryArticleList(null,null,"all","all")).thenReturn(List.of(one,two));
        when(help.queryArticleDetail(1L)).thenReturn(detail("### 查看记录\n\n"+source));
        HelpArticleDetailRet option=detail(source);option.setArticleId(2L);when(help.queryArticleDetail(2L)).thenReturn(option);
        when(gateway.interpret(anyList())).thenReturn(response("{\"articleIds\":[\"1\",\"2\"]}"),
            response("{\"supported\":true,\"passageIds\":[\"1:p1\",\"2:p1\"]}"));
        AgentSkillExecution result=service.answer("如何查看归还记录",null,()->{},r->{});
        assertNotNull(result.getEvidence());assertEquals(result.getAnswer().indexOf(source),result.getAnswer().lastIndexOf(source));
        assertEquals(1,result.getEvidence().getRecordCount());
    }
}
