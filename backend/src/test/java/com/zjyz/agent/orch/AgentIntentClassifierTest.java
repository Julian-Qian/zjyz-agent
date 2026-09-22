package com.zjyz.agent.orch;

import com.zjyz.agent.workspace.context.AgentTaskFrame;
import com.zjyz.agent.workspace.context.AgentTaskFrameBuilder;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIntentClassifierTest {
    private final AgentIntentClassifier classifier = new AgentIntentClassifier();

    @Test
    void projectTypeAndStatusCountsUseProjectListInDeterministicMode() {
        assertEquals(AgentIntentType.PROJECT_LIST, classifier.classify(
                "统计当前全部项目中租入、租出、进行中和已完成的数量"));
        assertEquals(AgentIntentType.PROJECT_LIST, classifier.classify(
                "租入项目有多少，租出项目有多少？"));
        assertEquals(AgentIntentType.PROJECT_LIST, classifier.classify(
                "进行中和已完成项目数量分别是多少？"));
    }

    @Test
    void materialAndDocumentFlowCountsDoNotBecomeProjectList() {
        for (String question : Arrays.asList(
                "本月租出材料数量是多少？",
                "本月归还材料数量是多少？",
                "本月赔偿材料数量和赔偿单数是多少？",
                "统计当前全部项目本月租出材料数量",
                "统计当前全部项目本月租入单、租出单数量",
                "本月各项目租入、租出流水汇总",
                "本月赔偿数量和单数是多少")) {
            assertEquals(AgentIntentType.PROJECT_RENT_MATERIALS, classifier.classify(question), question);
        }
    }

    @Test
    void proceduralHelpPrecedesBusinessDataKeywordMatchersAndMatchesTaskFrame() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList(
                "库存怎么查？",
                "合同字段怎么填？",
                "项目负责人怎么改？",
                "库存预警阈值怎么设置？",
                "新建项目需要哪些必填字段？",
                "合同有哪些必填项？")) {
            assertEquals(AgentIntentType.HELP_KNOWLEDGE, classifier.classify(question), question);
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(Collections.singletonList("help.search"), frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void explicitInventoryFactQueriesRemainInventory() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList("帮我查当前库存", "库存多少")) {
            assertEquals(AgentIntentType.INVENTORY_SUMMARY, classifier.classify(question), question);
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(Collections.singletonList("inventory.get_summary"), frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void prefixedPlainProjectCountUsesProjectListWithoutStealingFlowCounts() {
        for (String question : Arrays.asList(
                "统计全部项目数量",
                "E2E最终刷新恢复：统计全部项目数量")) {
            assertEquals(AgentIntentType.PROJECT_LIST, classifier.classify(question), question);
        }
        for (String question : Arrays.asList(
                "E2E最终刷新恢复：统计全部项目本月租出材料数量",
                "E2E最终刷新恢复：统计全部项目本月租出单数量")) {
            assertEquals(AgentIntentType.PROJECT_RENT_MATERIALS, classifier.classify(question), question);
        }
    }

    @Test
    void enterpriseUploadedKnowledgeNeverFallsThroughToContractOrProjectData() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList(
                "查询公司上传的制度、合同模板或操作规范。",
                "E2E企业知识边界：查询公司上传的制度、合同模板或操作规范。")) {
            assertEquals(AgentIntentType.ENTERPRISE_KNOWLEDGE_UNSUPPORTED,
                    classifier.classify(question), question);
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals("ENTERPRISE_KNOWLEDGE", frame.getDomain(), question);
            assertEquals(Collections.emptyList(), frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void contractStatusQueriesMatchBuilderForAllAndSingleScopes() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        assertContractStatus(builder, "哪些合同到期？", workspace());
        assertContractStatus(builder, "这个月有哪些合同到期", workspace());
        assertContractStatus(builder, "哪些项目还没有录入合同？", workspace());
        assertContractStatus(builder, "这个项目的合同状态是什么？", singleWorkspace());
        assertContractStatus(builder, "当前项目合同是否已到期？", singleWorkspace());
    }

    @Test
    void contractFieldsTemplatesAndEnterpriseKnowledgeDoNotBecomeContractStatus() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList("合同字段怎么填？", "合同模板怎么上传？")) {
            assertEquals(AgentIntentType.HELP_KNOWLEDGE, classifier.classify(question), question);
            assertEquals(Collections.singletonList("help.search"),
                    builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"))
                            .getMinimumRequiredTools(), question);
        }
        String enterprise = "查询公司上传的合同模板";
        assertEquals(AgentIntentType.ENTERPRISE_KNOWLEDGE_UNSUPPORTED,
                classifier.classify(enterprise));
        assertEquals(Collections.emptyList(), builder.build(
                enterprise, "", workspace(), ZoneId.of("Asia/Shanghai")).getMinimumRequiredTools());
    }

    @Test
    void financeCoreQuestionsUseExactDeterministicTools() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList(
                "公司现在应收应付、已收已付、未清多少",
                "上个月收了多少租金",
                "公司已收了多少钱",
                "公司回款多少",
                "公司实收多少",
                "全部项目已付多少钱")) {
            assertEquals(AgentIntentType.FINANCE_ENTERPRISE_KPI, classifier.classify(question), question);
            assertEquals(Collections.singletonList("finance.enterprise_kpi"),
                    builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai")).getMinimumRequiredTools(), question);
        }
        for (String question : Arrays.asList(
                "租入项目应付多少？", "供应商已付和未付分别多少？", "租入项目哪些应付到期？",
                "已经向各供应商支付了多少钱？", "还有多少供应商款没有支付？")) {
            assertEquals(AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY, classifier.classify(question), question);
            assertEquals(Collections.singletonList("finance.supplier_payable_summary"),
                    builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai")).getMinimumRequiredTools(), question);
        }
        assertEquals(AgentIntentType.PROJECT_SUMMARY, classifier.classify("这个项目已收多少"));
        assertEquals(AgentIntentType.FINANCE_ENTERPRISE_KPI,
                classifier.classify("这个项目现在应收、已收和未清多少？"));
        assertEquals(AgentIntentType.FINANCE_RECEIVABLE_COLLECTION,
                classifier.classify("这个项目有哪些逾期欠款需要催缴"));
        assertEquals(AgentIntentType.FINANCE_RECEIVABLE_COLLECTION,
                classifier.classify("这个项目逾期应收未清金额多少"));
        assertEquals(AgentIntentType.FINANCE_RECEIVABLE_COLLECTION,
                classifier.classify("这个租出项目有哪些逾期欠款需要催缴"));
        assertEquals(AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY,
                classifier.classify("租入项目已付和未付分别多少"));
        AgentTaskFrame futureDue = builder.build("哪些供应商快到付款日？", "", workspace(), ZoneId.of("Asia/Shanghai"));
        assertEquals(Collections.emptyList(), futureDue.getMinimumRequiredTools());
        assertEquals("UNSUPPORTED_BUSINESS", futureDue.getCoverage().name());
    }

    @Test
    void unsupportedFinanceMetricsRemainWithoutWeakToolCoverage() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList("公司利润多少", "公司营业额多少", "完整现金流是多少",
                "公司总资产多少", "本月税额多少", "还有多少押金没退")) {
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(Collections.emptyList(), frame.getMinimumRequiredTools(), question);
            assertEquals("UNSUPPORTED_BUSINESS", frame.getCoverage().name(), question);
        }
    }

    @Test
    void unsupportedMetricBarrierWinsOverEverySupportedFinanceFragment() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList(
                "公司应收应付和利润分别是多少？",
                "本月营业额和已收租金分别多少？",
                "供应商应付以及实际出账金额是多少？",
                "公司未退押金和应收应付分别多少？",
                "银行到账和租金回款分别是多少？",
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
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(AgentIntentType.UNSUPPORTED_BUSINESS, classifier.classify(question), question);
            assertEquals(Collections.emptyList(), frame.getMinimumRequiredTools(), question);
            assertEquals("UNSUPPORTED_BUSINESS", frame.getCoverage().name(), question);
        }

        for (String question : Arrays.asList(
                "公司现在应收应付、已收已付、未清多少",
                "上个月收了多少租金",
                "供应商已付和未付分别多少")) {
            assertTrue(classifier.classify(question) == AgentIntentType.FINANCE_ENTERPRISE_KPI
                    || classifier.classify(question) == AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY, question);
            assertFalse(builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"))
                    .getMinimumRequiredTools().isEmpty(), question);
        }
    }

    @Test
    void enterpriseKnowledgeAndProceduralHelpPrecedeUnsupportedFinancialFactBarrier() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList(
                "系统里的税费字段怎么填？",
                "银行流水怎么上传？",
                "总资产报表在哪里查看？",
                "未退押金怎么登记？")) {
            assertEquals(AgentIntentType.HELP_KNOWLEDGE, classifier.classify(question), question);
            assertEquals(Collections.singletonList("help.search"), builder.build(
                    question, "", workspace(), ZoneId.of("Asia/Shanghai")).getMinimumRequiredTools(), question);
        }
        for (String question : Arrays.asList(
                "查询公司上传的税费管理制度",
                "查询公司内部银行流水操作规范")) {
            assertEquals(AgentIntentType.ENTERPRISE_KNOWLEDGE_UNSUPPORTED, classifier.classify(question), question);
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals("ENTERPRISE_KNOWLEDGE", frame.getDomain(), question);
            assertEquals(Collections.emptyList(), frame.getMinimumRequiredTools(), question);
        }
    }

    @Test
    void historicalBalanceFormatsAreUnsupportedButCollectionPeriodsRemainRoutable() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList(
                "截至2026-07-31公司应收应付多少",
                "2026年7月公司应收应付多少",
                "2026-07公司应付多少",
                "7月份应付多少",
                "截至昨天公司应收应付多少",
                "公司上月财务总览",
                "去年企业财务台账")) {
            assertEquals(AgentIntentType.UNSUPPORTED_BUSINESS, classifier.classify(question), question);
            assertEquals(Collections.emptyList(), builder.build(
                    question, "", workspace(), ZoneId.of("Asia/Shanghai")).getMinimumRequiredTools(), question);
        }
        for (String question : Arrays.asList(
                "今年到期的应收催缴清单",
                "本年逾期应收有哪些",
                "历史欠款催缴清单")) {
            assertEquals(AgentIntentType.FINANCE_RECEIVABLE_COLLECTION, classifier.classify(question), question);
            assertEquals(Collections.singletonList("finance.receivable_collection_list"), builder.build(
                    question, "", workspace(), ZoneId.of("Asia/Shanghai")).getMinimumRequiredTools(), question);
        }
    }

    @Test
    void supplierPayablesNeverFallThroughToCustomerReceivableCollection() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList(
                "租入项目有哪些到期未付",
                "供应商有哪些到期未付",
                "供应商逾期付款有哪些",
                "当前供应商到期未付多少")) {
            assertEquals(AgentIntentType.FINANCE_SUPPLIER_PAYABLE_SUMMARY,
                    classifier.classify(question), question);
            assertEquals(Collections.singletonList("finance.supplier_payable_summary"), builder.build(
                    question, "", workspace(), ZoneId.of("Asia/Shanghai")).getMinimumRequiredTools(), question);
        }

        for (String question : Arrays.asList(
                "今年供应商到期未付多少",
                "截至2026-07-31逾期应收多少")) {
            assertEquals(AgentIntentType.UNSUPPORTED_BUSINESS, classifier.classify(question), question);
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(Collections.emptyList(), frame.getMinimumRequiredTools(), question);
            assertEquals("UNSUPPORTED_BUSINESS", frame.getCoverage().name(), question);
        }

        assertEquals(AgentIntentType.FINANCE_RECEIVABLE_COLLECTION,
                classifier.classify("今年到期的应收催缴清单"));
        assertEquals(Collections.singletonList("finance.receivable_collection_list"), builder.build(
                "今年到期的应收催缴清单", "", workspace(), ZoneId.of("Asia/Shanghai"))
                .getMinimumRequiredTools());
        assertEquals(AgentIntentType.FINANCE_RECEIVABLE_COLLECTION,
                classifier.classify("截至目前应收催缴清单"));
    }

    @Test
    void ownerActionCenterPhrasesUseTheSameDedicatedToolContract() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        for (String question : Arrays.asList(
                "老板行动中心",
                "今天老板最该优先处理哪些项目？",
                "项目风险待办排行",
                "哪些项目同时存在合同到期和待审核风险？",
                "今天最需要老板亲自处理的10件事是什么？")) {
            assertEquals(AgentIntentType.RISK_OWNER_ACTION_CENTER, classifier.classify(question), question);
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(Collections.singletonList("risk.owner_action_center"), frame.getMinimumRequiredTools(), question);
            assertEquals("SUPPORTED_TOOL", frame.getCoverage().name(), question);
        }
        assertEquals(AgentIntentType.RISK_OWNER_ACTION_CENTER,
                classifier.classify("这个项目有哪些风险需要处理？"));
        assertEquals(AgentIntentType.PROJECT_SUMMARY, classifier.classify("总结当前项目经营风险"));
        for (String question : Arrays.asList(
                "上个月最需要老板亲自处理的10件事是什么？",
                "预测下个月最需要老板亲自处理的10件事是什么？",
                "今天最需要老板亲自处理的10件供应商履约风险是什么？",
                "今天最需要老板亲自处理的10件供应商履约事项是什么？",
                "今天最需要老板亲自处理的10件客户流失事项是什么？",
                "今天最需要老板亲自处理的10件项目安全事项是什么？")) {
            assertEquals(AgentIntentType.UNSUPPORTED_BUSINESS, classifier.classify(question), question);
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(Collections.emptyList(), frame.getMinimumRequiredTools(), question);
            assertEquals("UNSUPPORTED_BUSINESS", frame.getCoverage().name(), question);
        }
    }

    @Test
    void singleRiskSummariesKeepTheirExistingToolsAndNonTodayActionSnapshotsAreUnsupported() {
        AgentTaskFrameBuilder builder = new AgentTaskFrameBuilder();
        java.util.Map<String, AgentIntentType> singleRiskCases = new java.util.LinkedHashMap<>();
        singleRiskCases.put("汇总库存异常", AgentIntentType.INVENTORY_SUMMARY);
        singleRiskCases.put("合同到期项目汇总", AgentIntentType.PROJECT_CONTRACT_STATUS);
        singleRiskCases.put("未对账项目汇总", AgentIntentType.PROJECT_RECONCILIATION_DUE);
        singleRiskCases.put("逾期应收汇总", AgentIntentType.FINANCE_RECEIVABLE_COLLECTION);
        singleRiskCases.forEach((question, expected) ->
                assertEquals(expected, classifier.classify(question), question));

        for (String question : Arrays.asList(
                "截至2026-07-31的老板行动中心",
                "上个月项目风险待办",
                "预测下个月哪些项目风险最高",
                "昨天的老板行动中心",
                "2025年项目风险待办",
                "8月项目风险排行",
                "近7天项目风险排行",
                "最近一个月老板行动中心",
                "昨日老板待办",
                "明年项目风险排行",
                "前一周项目风险待办",
                "前一个月老板行动中心",
                "近半年项目风险排行",
                "近半个月老板行动中心",
                "近一季度项目风险待办",
                "近一季项目风险待办")) {
            assertEquals(AgentIntentType.UNSUPPORTED_BUSINESS, classifier.classify(question), question);
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(Collections.emptyList(), frame.getMinimumRequiredTools(), question);
            assertEquals("UNSUPPORTED_BUSINESS", frame.getCoverage().name(), question);
        }
        assertEquals(AgentIntentType.RISK_OWNER_ACTION_CENTER,
                classifier.classify("截至目前老板行动中心"));
        assertEquals(AgentIntentType.RISK_OWNER_ACTION_CENTER,
                classifier.classify("截至现在老板行动中心"));
        assertEquals(AgentIntentType.RISK_OWNER_ACTION_CENTER,
                classifier.classify("截至今日老板行动中心"));

        for (String question : Arrays.asList(
                "我需要处理哪些供应商履约问题",
                "负责人应该处理哪些客户流失风险",
                "供应商风险排行")) {
            assertEquals(AgentIntentType.UNSUPPORTED_BUSINESS, classifier.classify(question), question);
            assertEquals("UNSUPPORTED_BUSINESS", builder.build(question, "", workspace(),
                    ZoneId.of("Asia/Shanghai")).getCoverage().name(), question);
        }

        for (String question : Arrays.asList(
                "老板应该处理合同到期和供应商履约风险",
                "合同到期和客户流失风险汇总",
                "待审核和供应商履约风险同时满足的项目",
                "老板应该优先处理哪些项目安全风险",
                "老板应该处理重点客户流失风险",
                "整体供应商履约风险排行",
                "这个项目有哪些供应商履约风险需要处理？",
                "这个项目有哪些客户流失风险需要处理？",
                "这个项目有哪些项目安全风险需要处理？",
                "老板应该处理合同到期和供应商履约",
                "待审核和客户流失需要处理")) {
            assertEquals(AgentIntentType.UNSUPPORTED_BUSINESS, classifier.classify(question), question);
            AgentTaskFrame frame = builder.build(question, "", workspace(), ZoneId.of("Asia/Shanghai"));
            assertEquals(Collections.emptyList(), frame.getMinimumRequiredTools(), question);
            assertEquals("UNSUPPORTED_BUSINESS", frame.getCoverage().name(), question);
        }

        for (String question : Arrays.asList(
                "老板行动中心：合同到期和未归还",
                "未对账和逾期应收风险汇总",
                "泛化老板行动中心",
                "项目风险排行",
                "经营风险待办",
                "高风险项目",
                "老板应该处理合同到期和材料未归还风险",
                "老板行动中心：合同到期和待审核单据",
                "老板行动中心：逾期应收款和未对账")) {
            assertEquals(AgentIntentType.RISK_OWNER_ACTION_CENTER, classifier.classify(question), question);
        }
    }

    private void assertContractStatus(AgentTaskFrameBuilder builder,
                                      String question,
                                      AgentRuntimeRecords.Workspace workspace) {
        assertEquals(AgentIntentType.PROJECT_CONTRACT_STATUS, classifier.classify(question), question);
        assertEquals(Collections.singletonList("project.contract_status"),
                builder.build(question, "", workspace, ZoneId.of("Asia/Shanghai"))
                        .getMinimumRequiredTools(), question);
    }

    private AgentRuntimeRecords.Workspace workspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("cid-1");
        workspace.setSelectionMode("ALL");
        workspace.setProjectIds(Collections.emptyList());
        return workspace;
    }

    private AgentRuntimeRecords.Workspace singleWorkspace() {
        AgentRuntimeRecords.Workspace workspace = new AgentRuntimeRecords.Workspace();
        workspace.setCid("cid-1");
        workspace.setScopeType("PROJECT");
        workspace.setSelectionMode("EXPLICIT");
        workspace.setProjectId("project-1");
        workspace.setProjectIds(Collections.singletonList("project-1"));
        return workspace;
    }
}
