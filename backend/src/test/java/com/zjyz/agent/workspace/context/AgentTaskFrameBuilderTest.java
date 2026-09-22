package com.zjyz.agent.workspace.context;

import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskFrameBuilderTest {
    private final AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();

    @Test
    void unrelatedNewQuestionDoesNotReusePreviousTask() {
        AgentTaskFrame frame = builder.build(
                "8月份有哪些项目应该进行对账了？",
                "今年我新建和录入了哪些项目？",
                allProjectsWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertEquals("SETTLEMENT", frame.getDomain());
        assertEquals("2026-08-01~2026-08-31", frame.getTimeRange());
        assertEquals("ALL", frame.getSelectionMode());
        assertFalse(frame.isFollowUp());
        assertTrue(frame.isRequiresBusinessData());
    }

    @Test
    void explicitReferenceInSameDomainIsTreatedAsFollowUp() {
        AgentTaskFrame frame = builder.build(
                "那这些项目的负责人呢？",
                "今年我新建和录入了哪些项目？",
                allProjectsWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertEquals("PROJECT", frame.getDomain());
        assertTrue(frame.isFollowUp());
    }

    @Test
    void operationQuestionUsesHelpKnowledgeInsteadOfBusinessQuery() {
        AgentTaskFrame frame = builder.build(
                "材料预估应该怎么操作？",
                "",
                allProjectsWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertEquals("HELP", frame.getDomain());
        assertEquals("HELP", frame.getTaskType());
        assertFalse(frame.isRequiresBusinessData());
    }

    @Test
    void separatesUnreconciledAndReceivableEvidenceRequirements() {
        AgentTaskFrame frame = builder.build(
                "哪些项目超过30天未对账且仍有应收欠款？",
                "",
                allProjectsWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertTrue(frame.getRequiredToolCodes().contains("project.reconciliation_due"));
        assertTrue(frame.getMinimumRequiredTools().contains("finance.receivable_collection_list"));
    }

    @Test
    void requiresContractAndMaterialEvidenceForExpiredUnreturnedQuestion() {
        AgentTaskFrame frame = builder.build(
                "哪些合同已经到期但材料仍未归还？",
                "",
                allProjectsWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertTrue(frame.getRequiredToolCodes().contains("project.contract_status"));
        assertTrue(frame.getRequiredToolCodes().contains("project.material_occupancy"));
    }

    @Test
    void requiresTransactionAggregateForYearlyRentOutRanking() {
        AgentTaskFrame frame = builder.build(
                "今年我租出最多的材料有哪些？",
                "",
                allProjectsWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertEquals("MATERIAL", frame.getDomain());
        assertEquals("SUMMARY", frame.getTaskType());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        assertEquals(today.getYear() + "-01-01~" + today, frame.getTimeRange());
        assertTrue(frame.getRequiredToolCodes().contains("material.transaction_aggregate"));
        assertFalse(frame.getRequiredToolCodes().contains("project.activity"));
    }

    @Test
    void requiresTransactionAggregateForPeriodCompensationWithoutMaterialKeyword() {
        AgentTaskFrame frame = builder.build(
                "本月赔偿数量和赔偿单次数分别是多少？",
                "",
                allProjectsWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertTrue(frame.getMinimumRequiredTools().contains("material.transaction_aggregate"));
        assertFalse(frame.getMinimumRequiredTools().contains("project.activity"));
    }

    @Test
    void currentUnreturnedQuestionRequiresOccupancyNotTransactionFlow() {
        AgentTaskFrame frame = builder.build(
                "目前还有哪些材料没有归还？",
                "",
                singleProjectWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertEquals(Collections.singletonList("project.material_occupancy"), frame.getMinimumRequiredTools());
    }

    @Test
    void enterpriseKnowledgeQuestionIsUnsupportedInsteadOfUsingSystemHelp() {
        for (String question : java.util.Arrays.asList(
                "公司制度里的赔偿单审核流程是什么？",
                "查询公司上传的制度、合同模板或操作规范。",
                "E2E企业知识边界：查询公司上传的制度、合同模板或操作规范。")) {
            AgentTaskFrame frame = builder.build(
                    question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));

            assertEquals("ENTERPRISE_KNOWLEDGE", frame.getDomain(), question);
            assertEquals(AgentTaskCoverage.UNSUPPORTED_BUSINESS, frame.getCoverage(), question);
            assertTrue(frame.isRequiresBusinessData(), question);
            assertTrue(frame.getMinimumRequiredTools().isEmpty(), question);
            assertFalse(frame.getMinimumRequiredTools().contains("project.contract_status"), question);
            assertFalse(frame.getMinimumRequiredTools().contains("help.search"), question);
        }
    }

    @Test
    void systemOperationQuestionStillRequiresHelpSearch() {
        AgentTaskFrame frame = builder.build(
                "系统里赔偿单应该怎么操作？",
                "",
                allProjectsWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertEquals(Collections.singletonList("help.search"), frame.getMinimumRequiredTools());
    }

    @Test
    void singleProjectCompositeReceivableUsesFinanceLedgerKpi() {
        AgentTaskFrame frame = builder.build(
                "这个项目累计应收、已收和欠款是多少？",
                "",
                singleProjectWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, frame.getCoverage());
        assertEquals(Collections.singletonList("finance.enterprise_kpi"), frame.getMinimumRequiredTools());
        assertEquals(Collections.singletonList("project-1"), frame.getProjectIds());

        for (AgentRuntimeRecords.Workspace ambiguousScope : java.util.Arrays.asList(
                allProjectsWorkspace(), multiProjectWorkspace())) {
            AgentTaskFrame ambiguous = builder.build(
                    "这个项目现在应收、已收和未清多少？", "", ambiguousScope, ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentTaskCoverage.UNSUPPORTED_BUSINESS, ambiguous.getCoverage());
            assertEquals(Collections.emptyList(), ambiguous.getMinimumRequiredTools());
        }
    }

    @Test
    void singleProjectCollectionEnforcementStillRequiresFinanceCollectionTool() {
        AgentTaskFrame frame = builder.build(
                "这个项目有哪些到期未付欠款需要催缴？",
                "",
                singleProjectWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertEquals(Collections.singletonList("finance.receivable_collection_list"),
                frame.getMinimumRequiredTools());

        AgentTaskFrame overdueAmount = builder.build(
                "这个项目逾期应收未清金额多少", "", singleProjectWorkspace(), ZoneId.of("Asia/Shanghai"));
        assertEquals(Collections.singletonList("finance.receivable_collection_list"),
                overdueAmount.getMinimumRequiredTools());
    }

    @Test
    void genericDueDateDoesNotPretendToBeContractStatus() {
        AgentTaskFrame frame = builder.build(
                "哪些项目已经到期？",
                "",
                allProjectsWorkspace(),
                ZoneId.of("Asia/Shanghai"));

        assertFalse(frame.getMinimumRequiredTools().contains("project.contract_status"));
        assertTrue(frame.isRequiresBusinessData());
        assertTrue(frame.getMinimumRequiredTools().isEmpty());
    }

    @Test
    void compensationAmountIsUnsupportedButCompensationQuantityUsesAggregate() {
        AgentTaskFrame amount = builder.build(
                "本月赔偿金额合计多少？", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
        AgentTaskFrame quantity = builder.build(
                "本月赔偿数量和单数是多少？", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));

        assertTrue(amount.isRequiresBusinessData());
        assertTrue(amount.getMinimumRequiredTools().isEmpty());
        assertEquals(Collections.singletonList("material.transaction_aggregate"),
                quantity.getMinimumRequiredTools());
    }

    @Test
    void unsupportedBusinessMetricsTakePriorityOverProjectListWords() {
        for (String question : java.util.Arrays.asList(
                "哪些项目利润最高？", "今年帮我赚到最多钱的材料是什么", "哪个材料给我挣得最多", "公司现金流怎么样？",
                "哪些客户有坏账风险？", "预测下月营业额", "各项目运输成本是多少？")) {
            AgentTaskFrame frame = builder.build(question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertTrue(frame.isRequiresBusinessData(), question);
            assertTrue(frame.getMinimumRequiredTools().isEmpty(), question);
        }
    }

    @Test
    void unsupportedFinanceMetricBarrierRejectsCompositeQuestionsAsAWhole() {
        for (String question : java.util.Arrays.asList(
                "公司应收应付和利润分别是多少？",
                "本月营业额和已收租金分别多少？",
                "供应商应付以及实际出账金额是多少？",
                "公司未退押金和应收应付分别多少？",
                "公司资产总额和应收分别多少？",
                "本月税金和已收租金分别多少？",
                "毛利和公司回款分别多少？",
                "本月营收和登记实收分别多少？",
                "资金净流入和供应商已付分别多少？",
                "银行到账金额和应收应付分别多少？",
                "退押金和公司回款分别多少？",
                "公司资产和应收应付分别多少？",
                "税金和应收应付分别多少？",
                "押金余额和应收应付分别多少？",
                "到账金额和应收应付分别多少？",
                "净利和应收应付分别多少？",
                "营业收入和应收应付分别多少？",
                "现金净流入和应收应付分别多少？")) {
            AgentTaskFrame frame = builder.build(question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals("FINANCE", frame.getDomain(), question);
            assertEquals(AgentTaskCoverage.UNSUPPORTED_BUSINESS, frame.getCoverage(), question);
            assertTrue(frame.isRequiresBusinessData(), question);
            assertEquals(Collections.emptyList(), frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void enterpriseSingleDirectionRegisteredCashUsesEnterpriseKpiBeforeReceivableFallback() {
        for (String question : java.util.Arrays.asList(
                "公司已收了多少钱",
                "公司回款多少",
                "公司实收多少",
                "全部项目已付多少钱",
                "企业登记收款",
                "总体登记付款")) {
            AgentTaskFrame frame = builder.build(question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, frame.getCoverage(), question);
            assertEquals(Collections.singletonList("finance.enterprise_kpi"),
                    frame.getMinimumRequiredTools(), question);
        }

        assertEquals(Collections.singletonList("project.get_summary"), builder.build(
                "这个项目已收多少", "", singleProjectWorkspace(), ZoneId.of("Asia/Shanghai"))
                .getMinimumRequiredTools());
        assertEquals(Collections.singletonList("finance.enterprise_kpi"), builder.build(
                "这个项目现在应收、已收和未清多少？", "", singleProjectWorkspace(), ZoneId.of("Asia/Shanghai"))
                .getMinimumRequiredTools());
        assertEquals(Collections.singletonList("finance.receivable_collection_list"), builder.build(
                "这个租出项目有哪些逾期欠款需要催缴", "", singleProjectWorkspace(), ZoneId.of("Asia/Shanghai"))
                .getMinimumRequiredTools());
        assertEquals(Collections.singletonList("finance.supplier_payable_summary"), builder.build(
                "租入项目已付和未付分别多少", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"))
                .getMinimumRequiredTools());
    }

    @Test
    void historicalReceivableCollectionIntentPrecedesHistoricalBalanceBarrier() {
        for (String question : java.util.Arrays.asList(
                "今年到期的应收催缴清单",
                "本年逾期应收有哪些",
                "历史欠款催缴清单")) {
            AgentTaskFrame frame = builder.build(question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, frame.getCoverage(), question);
            assertEquals(Collections.singletonList("finance.receivable_collection_list"),
                    frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void separatesSupplierPayableDirectionFromCustomerCollectionAndHistoricalBalances() {
        for (String question : java.util.Arrays.asList(
                "租入项目有哪些到期未付",
                "供应商有哪些到期未付",
                "供应商逾期付款有哪些",
                "当前供应商到期未付多少")) {
            AgentTaskFrame frame = builder.build(question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, frame.getCoverage(), question);
            assertEquals(Collections.singletonList("finance.supplier_payable_summary"),
                    frame.getMinimumRequiredTools(), question);
        }

        for (String question : java.util.Arrays.asList(
                "今年供应商到期未付多少",
                "截至2026-07-31逾期应收多少")) {
            AgentTaskFrame frame = builder.build(question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentTaskCoverage.UNSUPPORTED_BUSINESS, frame.getCoverage(), question);
            assertEquals(Collections.emptyList(), frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void supportedEstimateAndSingleProjectSummaryRemainRoutable() {
        AgentTaskFrame estimate = builder.build(
                "这个工程需要多少材料？", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
        AgentTaskFrame summary = builder.build(
                "总结这个项目的经营概况和风险建议", "", singleProjectWorkspace(), ZoneId.of("Asia/Shanghai"));

        assertEquals(Collections.singletonList("material.estimate"), estimate.getMinimumRequiredTools());
        assertEquals(Collections.singletonList("project.get_summary"), summary.getMinimumRequiredTools());
    }

    @Test
    void unknownRealtimeBusinessFactsRemainUnsupportedDespiteWeakRelatedTools() {
        for (String question : java.util.Arrays.asList(
                "今天哪些项目需要送货？",
                "目前公司总资产多少？",
                "截至目前哪些客户最可能流失？",
                "当前供应商履约情况怎么样？")) {
            AgentTaskFrame frame = builder.build(question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertTrue(frame.isRequiresBusinessData(), question);
            assertTrue(frame.getMinimumRequiredTools().isEmpty(), question);
        }
    }

    @Test
    void documentAuditQuestionsBindAuditListTool() {
        // D2 起单据审核为已支持能力，不再落入诚实拒答。
        for (String question : java.util.Arrays.asList(
                "今天有哪些待审核单据？",
                "有哪些单据还没复核",
                "列出未审核的赔偿单")) {
            AgentTaskFrame frame = builder.build(question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertTrue(frame.getMinimumRequiredTools().contains("document.audit_list"), question);
        }
    }

    @Test
    void compensationFeeEntryOperationUsesSystemHelpBeforeBusinessMetricGuard() {
        AgentTaskFrame frame = builder.build(
                "系统里赔偿费用怎么录入？", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));

        assertFalse(frame.isRequiresBusinessData());
        assertEquals(Collections.singletonList("help.search"), frame.getMinimumRequiredTools());
    }

    @Test
    void operationConfigurationAndFieldQuestionsTakePriorityOverDataMatchers() {
        for (String question : java.util.Arrays.asList(
                "库存预警阈值怎么设置？",
                "新建项目需要哪些必填字段？",
                "合同有哪些必填项？",
                "库存怎么查？",
                "合同字段怎么填？",
                "项目负责人怎么改？",
                "系统里的税费字段怎么填？",
                "银行流水怎么上传？",
                "总资产报表在哪里查看？",
                "未退押金怎么登记？")) {
            AgentTaskFrame frame = builder.build(question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals("HELP", frame.getDomain(), question);
            assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, frame.getCoverage(), question);
            assertFalse(frame.isRequiresBusinessData(), question);
            assertEquals(Collections.singletonList("help.search"), frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void howBarrierSeparatesProceduralHelpFactsAndUnknownActions() {
        AgentTaskFrame procedural = builder.build(
                "怎么查库存？", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
        AgentTaskFrame imperativeFact = builder.build(
                "帮我查当前库存", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
        AgentTaskFrame inventoryFact = builder.build(
                "库存情况如何？", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
        AgentTaskFrame projectFact = builder.build(
                "这个项目经营情况如何？", "", singleProjectWorkspace(), ZoneId.of("Asia/Shanghai"));
        AgentTaskFrame unknownHow = builder.build(
                "项目怎么分组？", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));

        assertEquals(Collections.singletonList("help.search"), procedural.getMinimumRequiredTools());
        assertEquals(Collections.singletonList("inventory.get_summary"), imperativeFact.getMinimumRequiredTools());
        assertEquals(Collections.singletonList("inventory.get_summary"), inventoryFact.getMinimumRequiredTools());
        assertEquals(Collections.singletonList("project.get_summary"), projectFact.getMinimumRequiredTools());
        assertEquals(AgentTaskCoverage.UNSUPPORTED_BUSINESS, unknownHow.getCoverage());
        assertTrue(unknownHow.getMinimumRequiredTools().isEmpty());
    }

    @Test
    void overlappingFactQueriesStillUseDeterministicDataTools() {
        AgentTaskFrame contracts = builder.build(
                "哪些合同到期？", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
        AgentTaskFrame inventory = builder.build(
                "库存多少？", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
        AgentTaskFrame projects = builder.build(
                "今年新建了哪些项目？", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));

        assertEquals(Collections.singletonList("project.contract_status"), contracts.getMinimumRequiredTools());
        assertEquals(Collections.singletonList("inventory.get_summary"), inventory.getMinimumRequiredTools());
        assertEquals(Collections.singletonList("project.list"), projects.getMinimumRequiredTools());
        assertTrue(contracts.isRequiresBusinessData());
        assertTrue(inventory.isRequiresBusinessData());
        assertTrue(projects.isRequiresBusinessData());
    }

    @Test
    void explicitCoverageDefaultsUnmappedQueriesToUnsupportedBusiness() {
        for (String question : java.util.Arrays.asList(
                "还有多少押金没退？",
                "明天有几车要发？",
                "发车安排拿来",
                "租金汇总")) {
            AgentTaskFrame frame = builder.build(question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentTaskCoverage.UNSUPPORTED_BUSINESS, frame.getCoverage(), question);
            assertTrue(frame.isRequiresBusinessData(), question);
            assertTrue(frame.getMinimumRequiredTools().isEmpty(), question);
        }
    }

    @Test
    void naturalAllProjectListPhraseUsesProjectCatalogCoverage() {
        AgentTaskFrame frame = builder.build(
                "给我所有项目的列表", "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));

        assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, frame.getCoverage());
        assertEquals(Collections.singletonList("project.list"), frame.getMinimumRequiredTools());
    }

    @Test
    void projectTypeAndStatusCountsUseProjectListInsteadOfMaterialFlowAggregate() {
        for (String question : java.util.Arrays.asList(
                "统计当前全部项目中租入、租出、进行中和已完成的数量",
                "租入项目有多少，租出项目有多少？",
                "进行中和已完成项目数量分别是多少？")) {
            AgentTaskFrame frame = builder.build(
                    question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));

            assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, frame.getCoverage(), question);
            assertEquals(Collections.singletonList("project.list"), frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void plainAllProjectCountWithEvaluationPrefixUsesProjectListOnly() {
        for (String question : java.util.Arrays.asList(
                "统计全部项目数量",
                "E2E最终刷新恢复：统计全部项目数量")) {
            AgentTaskFrame frame = builder.build(
                    question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));

            assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, frame.getCoverage(), question);
            assertEquals(Collections.singletonList("project.list"), frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void prefixedMaterialAndDocumentCountsDoNotBecomePlainProjectCounts() {
        for (String question : java.util.Arrays.asList(
                "E2E最终刷新恢复：统计全部项目本月租出材料数量",
                "E2E最终刷新恢复：统计全部项目本月租出单数量")) {
            AgentTaskFrame frame = builder.build(
                    question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));

            assertEquals(Collections.singletonList("material.transaction_aggregate"),
                    frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void periodMaterialAndDocumentFlowCountsRemainMaterialAggregates() {
        for (String question : java.util.Arrays.asList(
                "本月租出材料数量是多少？",
                "本月归还材料数量是多少？",
                "本月赔偿材料数量和赔偿单数是多少？",
                "统计当前全部项目本月租出材料数量",
                "统计当前全部项目本月租入单、租出单数量",
                "本月各项目租入、租出流水汇总",
                "本月赔偿数量和单数是多少")) {
            AgentTaskFrame frame = builder.build(
                    question, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));

            assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, frame.getCoverage(), question);
            assertEquals(Collections.singletonList("material.transaction_aggregate"),
                    frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void generalAdviceAndChitchatRemainNonBusiness() {
        for (String message : java.util.Arrays.asList(
                "客户沟通时应该注意什么？",
                "我们公司最近团建去哪比较好？",
                "你好", "谢谢", "你能做什么")) {
            AgentTaskFrame frame = builder.build(message, "", allProjectsWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentTaskCoverage.NON_BUSINESS, frame.getCoverage(), message);
            assertFalse(frame.isRequiresBusinessData(), message);
            assertTrue(frame.getMinimumRequiredTools().isEmpty(), message);
        }
    }

    @Test
    void ownerActionCenterRequiresExactCanonicalToolForAllMultiAndSingle() {
        for (AgentRuntimeRecords.Workspace workspace : java.util.Arrays.asList(
                allProjectsWorkspace(), multiProjectWorkspace(), singleProjectWorkspace())) {
            AgentTaskFrame frame = builder.build(
                    "老板行动中心：列出同时存在未归还和待审核的项目",
                    "", workspace, ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, frame.getCoverage());
            assertTrue(frame.isRequiresBusinessData());
            assertEquals(Collections.singletonList("risk.owner_action_center"), frame.getMinimumRequiredTools());
            assertEquals("RISK", frame.getDomain());
        }
    }

    @Test
    void deicticProjectRiskActionRequiresExactlyOneFrozenProject() {
        AgentTaskFrame single = builder.build(
                "这个项目有哪些风险需要处理？", "", singleProjectWorkspace(), ZoneId.of("Asia/Shanghai"));
        assertEquals(AgentTaskCoverage.SUPPORTED_TOOL, single.getCoverage());
        assertEquals(Collections.singletonList("risk.owner_action_center"), single.getMinimumRequiredTools());

        for (AgentRuntimeRecords.Workspace workspace : java.util.Arrays.asList(
                allProjectsWorkspace(), multiProjectWorkspace())) {
            AgentTaskFrame frame = builder.build(
                    "这个项目有哪些风险需要处理？", "", workspace, ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentTaskCoverage.UNSUPPORTED_BUSINESS, frame.getCoverage());
            assertTrue(frame.getMinimumRequiredTools().isEmpty());
        }

        for (String question : java.util.Arrays.asList(
                "这个项目有哪些供应商履约风险需要处理？",
                "这个项目有哪些客户流失风险需要处理？",
                "这个项目有哪些项目安全风险需要处理？")) {
            AgentTaskFrame frame = builder.build(question, "", singleProjectWorkspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentTaskCoverage.UNSUPPORTED_BUSINESS, frame.getCoverage(), question);
            assertTrue(frame.getMinimumRequiredTools().isEmpty(), question);
        }
    }

    private AgentRuntimeRecords.Workspace allProjectsWorkspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("ALL");
        workspace.setProjectIds(Collections.emptyList());
        return workspace;
    }

    private AgentRuntimeRecords.Workspace singleProjectWorkspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("EXPLICIT");
        workspace.setProjectId("project-1");
        workspace.setProjectIds(Collections.singletonList("project-1"));
        return workspace;
    }

    private AgentRuntimeRecords.Workspace multiProjectWorkspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setScopeType("TENANT");
        workspace.setSelectionMode("EXPLICIT");
        workspace.setProjectIds(java.util.Arrays.asList("project-1", "project-2"));
        return workspace;
    }
}
