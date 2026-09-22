package com.zjyz.agent.workspace.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.FailedDimension;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.ProjectAction;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.Query;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.Result;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.UnsupportedDimension;
import com.zjyz.common.exception.MyBizException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerActionCenterSkillTest {
    @Test
    void parsesAllIntersectionAndTopNWithoutAllowingModelToSetScoringInputs() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        when(service.generate(any(), any())).thenReturn(emptyResult());
        OwnerActionCenterSkill skill = new OwnerActionCenterSkill(service, new ObjectMapper());

        skill.execute("{}", "列出同时存在未归还和待审核风险的前5个项目", workspace());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(service).generate(any(), query.capture());
        assertEquals("ALL", query.getValue().getMatchMode());
        assertEquals(5, query.getValue().getLimit());
        assertTrue(query.getValue().getRiskTypes().contains("MATERIAL_OUTSTANDING"));
        assertTrue(query.getValue().getRiskTypes().contains("PENDING_REVIEW"));

        assertEquals("AGT400", assertThrows(MyBizException.class, () -> skill.execute(
                "{\"asOfDate\":\"2026-01-01\"}", "老板行动中心", workspace())).getErrorCode());
    }

    @Test
    void modelCannotBroadenProjectStatusOrIntersectionWithoutOriginalMessageAuthorization() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        when(service.generate(any(), any())).thenReturn(emptyResult());
        OwnerActionCenterSkill skill = new OwnerActionCenterSkill(service, new ObjectMapper());

        skill.execute("{\"projectStatus\":\"ALL\",\"matchMode\":\"ALL\",\"riskTypes\":[\"PENDING_REVIEW\"]}",
                "老板行动中心", workspace());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(service).generate(any(), query.capture());
        assertEquals("ONGOING", query.getValue().getProjectStatus());
        assertEquals("ANY", query.getValue().getMatchMode());
        assertTrue(query.getValue().getRiskTypes().isEmpty());
        assertEquals(20, query.getValue().getLimit());

        assertEquals("AGT400", assertThrows(MyBizException.class, () -> skill.execute(
                "{}", "截至2026-07-31的老板行动中心", workspace())).getErrorCode());
        assertEquals("AGT400", assertThrows(MyBizException.class, () -> skill.execute(
                "{}", "预测下个月哪些项目风险最高", workspace())).getErrorCode());
        for (String message : java.util.Arrays.asList(
                "近7天项目风险排行", "最近一个月老板行动中心", "昨日老板待办", "明年项目风险排行",
                "近半年项目风险排行", "近半个月老板行动中心", "近一季度项目风险待办", "近一季项目风险待办")) {
            assertEquals("AGT400", assertThrows(MyBizException.class,
                    () -> skill.execute("{}", message, workspace())).getErrorCode(), message);
        }
    }

    @Test
    void unknownRiskClauseMakesTheWholeDirectExecutionFailClosed() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        OwnerActionCenterSkill skill = new OwnerActionCenterSkill(service, new ObjectMapper());

        for (String message : java.util.Arrays.asList(
                "老板应该处理合同到期和供应商履约风险",
                "合同到期和客户流失风险汇总",
                "待审核和供应商履约风险同时满足的项目",
                "老板应该优先处理哪些项目安全风险",
                "老板应该处理重点客户流失风险",
                "整体供应商履约风险排行",
                "老板应该处理合同到期和供应商履约",
                "待审核和客户流失需要处理",
                "老板应该处理材料质量风险",
                "老板应该处理单据造假风险")) {
            assertEquals("AGT400", assertThrows(MyBizException.class,
                    () -> skill.execute("{}", message, workspace())).getErrorCode(), message);
        }
        verify(service, org.mockito.Mockito.never()).generate(any(), any());
    }

    @Test
    void naturalEntityVariantsRemainCoveredWithoutAllowingBareEntityWords() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        when(service.generate(any(), any())).thenReturn(emptyResult());
        OwnerActionCenterSkill skill = new OwnerActionCenterSkill(service, new ObjectMapper());

        for (String message : java.util.Arrays.asList(
                "老板应该处理合同到期和材料未归还风险",
                "老板行动中心：合同到期和待审核单据",
                "老板行动中心：逾期应收款和未对账")) {
            skill.execute("{}", message, workspace());
        }

        verify(service, org.mockito.Mockito.times(3)).generate(any(), any());
    }

    @Test
    void todayOwnerTopActionsUsesOriginalTenItemLimitAndKeepsBoundaries() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        when(service.generate(any(), any())).thenReturn(emptyResult());
        OwnerActionCenterSkill skill = new OwnerActionCenterSkill(service, new ObjectMapper());

        skill.execute("{\"limit\":3,\"riskTypes\":[\"PENDING_REVIEW\"]}",
                "今天最需要老板亲自处理的10件事是什么？", workspace());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(service).generate(any(), query.capture());
        assertEquals(10, query.getValue().getLimit());
        assertTrue(query.getValue().getRiskTypes().isEmpty());
        assertEquals("ONGOING", query.getValue().getProjectStatus());
        assertEquals("ANY", query.getValue().getMatchMode());

        for (String message : java.util.Arrays.asList(
                "上个月最需要老板亲自处理的10件事是什么？",
                "预测下个月最需要老板亲自处理的10件事是什么？",
                "今天最需要老板亲自处理的10件供应商履约风险是什么？",
                "今天最需要老板亲自处理的10件供应商履约事项是什么？",
                "今天最需要老板亲自处理的10件客户流失事项是什么？",
                "今天最需要老板亲自处理的10件项目安全事项是什么？")) {
            assertEquals("AGT400", assertThrows(MyBizException.class,
                    () -> skill.execute("{}", message, workspace())).getErrorCode(), message);
        }
    }

    @Test
    void deicticProjectRiskActionRequiresSingleScopeAndRejectsUnknownDimensions() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        when(service.generate(any(), any())).thenReturn(emptyResult());
        OwnerActionCenterSkill skill = new OwnerActionCenterSkill(service, new ObjectMapper());
        AgentRuntimeRecords.Workspace single = workspace();
        single.setSelectionMode("EXPLICIT");
        single.setProjectId("project-1");
        single.setProjectIds(Collections.singletonList("project-1"));

        AgentSkillExecution execution = skill.execute(
                "{\"limit\":3}", "这个项目有哪些风险需要处理？", single);
        assertEquals("risk_owner_action_center", execution.getIntent());
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(service).generate(any(), query.capture());
        assertEquals(20, query.getValue().getLimit());

        AgentRuntimeRecords.Workspace multi = workspace();
        multi.setSelectionMode("EXPLICIT");
        multi.setProjectIds(java.util.Arrays.asList("project-1", "project-2"));
        assertEquals("AGT400", assertThrows(MyBizException.class,
                () -> skill.execute("{}", "这个项目有哪些风险需要处理？", workspace())).getErrorCode());
        assertEquals("AGT400", assertThrows(MyBizException.class,
                () -> skill.execute("{}", "这个项目有哪些风险需要处理？", multi)).getErrorCode());
        for (String question : java.util.Arrays.asList(
                "这个项目有哪些供应商履约风险需要处理？",
                "这个项目有哪些客户流失风险需要处理？",
                "这个项目有哪些项目安全风险需要处理？")) {
            assertEquals("AGT400", assertThrows(MyBizException.class,
                    () -> skill.execute("{}", question, single)).getErrorCode(), question);
        }
    }

    @Test
    void originalMessageIsTheOnlyAuthorityForRiskTypesAndLimit() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        when(service.generate(any(), any())).thenReturn(emptyResult());
        OwnerActionCenterSkill skill = new OwnerActionCenterSkill(service, new ObjectMapper());

        skill.execute("{\"riskTypes\":[\"OVERDUE_RECEIVABLE\"],\"limit\":3}",
                "老板行动中心：同时检查合同到期和待审核的前7个项目", workspace());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(service).generate(any(), query.capture());
        assertEquals(java.util.Arrays.asList("CONTRACT_EXPIRED", "PENDING_REVIEW"), query.getValue().getRiskTypes());
        assertEquals("ALL", query.getValue().getMatchMode());
        assertEquals(7, query.getValue().getLimit());
    }

    @Test
    void failedDimensionIsDisclosedBeforeAvailableDimensionRanking() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        Result result = emptyResult();
        result.setScoreIncomplete(true);
        result.setRankingBasis("AVAILABLE_DIMENSIONS");
        FailedDimension failed = new FailedDimension();
        failed.setRiskType("OVERDUE_RECEIVABLE"); failed.setErrorCode("FIN409"); failed.setReason("orphan ledger");
        result.setFailedDimensions(Collections.singletonList(failed));
        ProjectAction item = new ProjectAction();
        item.setProjectId("P1"); item.setProjectName("项目A"); item.setTotalScore(40); item.setRiskCount(1);
        result.setItems(Collections.singletonList(item));
        result.setProjectTotalCount(1); result.setDisplayedProjectCount(1); result.setActionTotalCount(1);
        when(service.generate(any(), any())).thenReturn(result);

        AgentSkillExecution execution = new OwnerActionCenterSkill(service, new ObjectMapper())
                .execute("{}", "老板行动中心", workspace());

        assertTrue(execution.getAnswer().startsWith("注意：有风险维度核算失败"));
        assertTrue(execution.getAnswer().contains("不能视为完整或最高风险排名"));
        assertEquals(true, execution.getCards().get(0).get("scoreIncomplete"));
        assertEquals("AVAILABLE_DIMENSIONS", execution.getCards().get(0).get("rankingBasis"));
    }

    @Test
    void unsupportedDimensionUsesHonestBoundaryAndDoesNotClaimNoRisk() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        Result result = emptyResult();
        result.setScoreIncomplete(true);
        result.setRankingBasis("AVAILABLE_DIMENSIONS");
        UnsupportedDimension unsupported = new UnsupportedDimension();
        unsupported.setRiskType("INVENTORY_ANOMALY");
        unsupported.setReason("库存异常不能可靠归属项目");
        result.setUnsupportedDimensions(Collections.singletonList(unsupported));
        when(service.generate(any(), any())).thenReturn(result);

        AgentSkillExecution execution = new OwnerActionCenterSkill(service, new ObjectMapper())
                .execute("{}", "老板行动中心", workspace());

        assertTrue(execution.getAnswer().startsWith("注意：有风险维度当前不支持或未启用"));
        assertTrue(execution.getAnswer().contains("当前可评估维度中没有匹配项目"));
        assertTrue(execution.getAnswer().contains("不能据此判断为无风险"));
    }

    @Test
    void partialApplicabilityIsDisclosedEvenWithoutFailedOrUnsupportedDimension() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        Result result = emptyResult();
        result.setScoreIncomplete(true);
        result.setRankingBasis("AVAILABLE_DIMENSIONS");
        ProjectAction item = new ProjectAction();
        item.setProjectId("P1"); item.setProjectName("租出项目"); item.setTotalScore(30); item.setRiskCount(1);
        result.setItems(Collections.singletonList(item));
        result.setProjectTotalCount(1); result.setDisplayedProjectCount(1); result.setActionTotalCount(1);
        when(service.generate(any(), any())).thenReturn(result);

        AgentSkillExecution execution = new OwnerActionCenterSkill(service, new ObjectMapper())
                .execute("{}", "老板行动中心", workspace());

        assertTrue(execution.getAnswer().startsWith("注意：部分项目不适用所选风险维度"));
        assertTrue(execution.getAnswer().contains("不能视为完整或最高风险排名"));
    }

    @Test
    void simultaneousWithoutNamedRiskDoesNotForceAllDimensions() {
        OwnerActionCenterService service = mock(OwnerActionCenterService.class);
        when(service.generate(any(), any())).thenReturn(emptyResult());
        OwnerActionCenterSkill skill = new OwnerActionCenterSkill(service, new ObjectMapper());

        skill.execute("{}", "老板行动中心同时有哪些事情要处理", workspace());

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(service).generate(any(), query.capture());
        assertEquals("ANY", query.getValue().getMatchMode());
        assertTrue(query.getValue().getRiskTypes().isEmpty());
    }

    private Result emptyResult() {
        Result result = new Result();
        result.setAsOfDate("2026-08-31");
        result.setQuery(new Query());
        result.setTotalProjectCount(0); result.setProjectRiskCount(0); result.setProjectTotalCount(0);
        result.setDisplayedProjectCount(0); result.setActionTotalCount(0); result.setActionDisplayedCount(0);
        result.setTotalCount(0); result.setDisplayedCount(0); result.setTruncated(false); result.setLimit(20);
        return result;
    }

    private AgentRuntimeRecords.Workspace workspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("CID-A"); workspace.setSelectionMode("ALL"); workspace.setProjectIds(Collections.emptyList());
        return workspace;
    }
}
