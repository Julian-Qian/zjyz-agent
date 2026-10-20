package com.zjyz.agent.workspace.v2.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AgentAnalysisComposerTest {
    private final ObjectMapper json=new ObjectMapper();
    private final AgentAnalysisComposer composer=new AgentAnalysisComposer(mock(AgentV2ModelGateway.class),json);
    @Test void rejectsInventedReferenceAndAmount() throws Exception {
        assertFalse(composer.validClaims(json.readTree("[{\"type\":\"FACT\",\"text\":\"未归还100根\",\"evidenceIds\":[\"fake\"]}]"),Map.of("e1","未归还100根")));
        assertFalse(composer.validClaims(json.readTree("[{\"type\":\"FACT\",\"text\":\"收入200元\",\"evidenceIds\":[\"e1\"]}]"),Map.of("e1","收入100元")));
    }
    @Test void acceptsGroundedClaimsAndRejectsUncitedProse() throws Exception {
        assertTrue(composer.validClaims(json.readTree("[{\"type\":\"FACT\",\"text\":\"未归还100根\",\"evidenceIds\":[\"e1\"]}]"),Map.of("e1","未归还100根")));
        assertTrue(composer.validClaims(json.readTree("[{\"type\":\"RECOMMENDATION\",\"text\":\"1. 优先催收12500元欠款\",\"evidenceIds\":[\"e1\"]}]"),Map.of("e1","应收12,500.00元")));
        assertFalse(composer.validClaims(json.readTree("[{\"type\":\"INFERENCE\",\"text\":\"利润很好\",\"evidenceIds\":[]}]"),Map.of("e1","现金登记")));
    }
    @Test void groupedAnalysisLeadsWithFindingAndPreservesCitationsAndLimitations() {
        AgentV2ModelGateway gateway=mock(AgentV2ModelGateway.class);
        com.zjyz.agent.workspace.modelgateway.AgentModelGateway.ModelResult draft=new com.zjyz.agent.workspace.modelgateway.AgentModelGateway.ModelResult();
        draft.setSuccess(true);draft.setContent("{\"claims\":[{\"type\":\"FACT\",\"text\":\"未归还100根\",\"evidenceIds\":[\"e1\"]},{\"type\":\"INFERENCE\",\"text\":\"仍有材料待归还\",\"evidenceIds\":[\"e1\"]},{\"type\":\"RECOMMENDATION\",\"text\":\"建议核对归还计划\",\"evidenceIds\":[\"e1\"]},{\"type\":\"LIMITATION\",\"text\":\"无法确定实际归还日期\",\"evidenceIds\":[\"e1\"]}]}");
        com.zjyz.agent.workspace.modelgateway.AgentModelGateway.ModelResult review=new com.zjyz.agent.workspace.modelgateway.AgentModelGateway.ModelResult();review.setSuccess(true);review.setContent("{\"supported\":true}");
        when(gateway.isAvailable()).thenReturn(true);when(gateway.complete(anyList(),anyList(),anyString())).thenReturn(draft,review);
        com.zjyz.agent.orch.AgentSkillExecution execution=new com.zjyz.agent.orch.AgentSkillExecution();execution.setAnswer("未归还100根，缺少实际归还日期。");
        execution.setCards(java.util.List.of());execution.setWarnings(java.util.List.of());execution.setEvidence(new com.zjyz.agent.model.AgentEvidence());
        AgentAnalysisComposer.Result result=new AgentAnalysisComposer(gateway,json).compose("分析归还情况",java.util.List.of(execution),()->{},r->{});
        assertTrue(result.success);assertTrue(result.answer.startsWith("**分析**"));
        assertTrue(result.answer.indexOf("**事实**")<result.answer.indexOf("**建议**"));
        assertTrue(result.answer.contains("100根"));assertTrue(result.answer.contains("无法确定实际归还日期"));assertEquals(4,result.claims.size());
        assertEquals(java.util.List.of("e1"),result.claims.get(0).get("evidenceIds"));
    }

    @Test void deterministicPyramidSynthesizesClearTopDownReportFromMultiToolEvidence() {
        com.zjyz.agent.orch.AgentSkillExecution e1 = exec("finance.payment_ledger", "AVAILABLE", "COMPLETE",
                "2025-10-18 至 2026-10-18，系统客户登记实收 0.00 元，其中租金 0.00 元、赔偿 0.00 元。以上按付款日期统计，不代表银行实际到账或出账，也不等同于已用于冲抵本金的金额。");
        com.zjyz.agent.orch.AgentSkillExecution e2 = exec("finance.material_settled_rent", "MISSING_DATA", "PARTIAL",
                "已结算材料租金数据覆盖或金额核对不完整（4/21 张），本次不输出全量排名，避免把部分历史当作最高收入结论。");
        com.zjyz.agent.orch.AgentSkillExecution e3 = exec("inventory.materials", "AVAILABLE", "COMPLETE",
                "当前库存材料共 387 种。库存较低材料示例：扣件(-1374)、大袋子(-1309)。");
        com.zjyz.agent.orch.AgentSkillExecution e4 = exec("inventory.operations", "AVAILABLE", "COMPLETE",
                "企业全部库存中，当前异常材料共 90 种（库存为负 24、在租为负 20、租入未退为负 0、长期无流水 60）。\n• 全部异常匹配 104 项，结果卡展示前 50 项，材料明细见结果卡。\n• 不同材料的数量单位未混合相加。");
        com.zjyz.agent.orch.AgentSkillExecution e5 = exec("project.activity", "AVAILABLE", "COMPLETE",
                "截至 2026-10-18，当前范围共有 9 个项目达到连续 0 天无所选业务活动。前3项：万华新工地（最近活动：2026-06-14，无活动126天）等，完整名单见结果卡。");
        com.zjyz.agent.orch.AgentSkillExecution e6 = exec("project.contract_status", "AVAILABLE", "COMPLETE",
                "截至2026年10月18日，有9个符合条件的合同：乐发学高桥日成项目-杂费测试（未填写到期日）等，共9个，完整名单见结果卡。");
        com.zjyz.agent.orch.AgentSkillExecution e7 = exec("finance.ledger_snapshot", "AVAILABLE", "COMPLETE",
                "截至 2026-10-18，租入项目正式已对账应付本金 0.00 元，供应商登记实付 0.00 元。登记实付与本金核销是不同口径，未分配本金付款为 0.00 元。");
        com.zjyz.agent.orch.AgentSkillExecution e8 = exec("material.outstanding_balance", "AVAILABLE", "COMPLETE",
                "截至 2026-10-18，已复核有效单据的实物未归还余额按单位为：60.0 根。不同材料的数量单位未混合相加。");

        AgentAnalysisComposer.Result res = composer.composeDeterministicPyramid(
                "帮我产出公司过去一年经营情况的经营报告",
                java.util.List.of(e1, e2, e3, e4, e5, e6, e7, e8),
                java.util.Collections.emptyList());

        assertTrue(res.success);
        assertTrue(res.answer.startsWith("**分析**\n\n- 【核心结论】"));
        assertTrue(res.answer.indexOf("**分析**") < res.answer.indexOf("**事实**"));
        assertTrue(res.answer.indexOf("**事实**") < res.answer.indexOf("**建议**"));
        assertTrue(res.answer.indexOf("**建议**") < res.answer.indexOf("**缺口**"));
        assertTrue(res.answer.contains("当前异常材料共 90 种"));
        assertTrue(res.answer.contains("4/21 张"));
        assertFalse(res.answer.contains("不同材料的数量单位未混合相加"));
    }

    @Test void deterministicPyramidHandlesMaterialSettledRentWithFallbackSettlementRecordsCleanly() {
        com.zjyz.agent.orch.AgentSkillExecution rent = exec("finance.material_settled_rent", "MISSING_DATA", "PARTIAL",
                "已结算材料租金数据覆盖或金额核对不完整（4/21 张），本次不输出全量排名，避免把部分历史当作最高收入结论。其中已通过快照核对的 4 张结算单局部样本内，已确认结算租金居前的材料为：1. 盘扣立杆（Φ48*3.25）12500.00 元（其余 17 张结算单缺少材料级分段明细，局部样本不代表全年全量最高收入）。");
        com.zjyz.agent.orch.AgentSkillExecution s1 = exec("finance.settlement_records", "AVAILABLE", "COMPLETE",
                "项目财务对账单共匹配 14 项，当前展示第 1 页，每页最多 50 项。");
        com.zjyz.agent.orch.AgentSkillExecution s2 = exec("finance.settlement_records", "AVAILABLE", "COMPLETE",
                "项目财务对账单共匹配 0 项，当前展示第 1 页，每页最多 50 项。");
        com.zjyz.agent.orch.AgentSkillExecution detail = exec("finance.settlement_detail", "AVAILABLE", "COMPLETE",
                "财务对账单详情查询已完成，详情见结果卡。");

        AgentAnalysisComposer.Result res = composer.composeDeterministicPyramid(
                "今年租出最赚钱的材料是什么（按租金收入）",
                java.util.List.of(rent, s1, s2, detail),
                java.util.Collections.emptyList());

        assertTrue(res.success);
        assertTrue(res.answer.startsWith("**分析**\n\n- 【核心结论】"));
        assertTrue(res.answer.contains("盘扣立杆"));
        assertFalse(res.answer.contains("当前展示第 1 页，每页最多 50 项"));
        assertFalse(res.answer.contains("财务对账单详情查询已完成"));
    }

    @Test void priceSandboxAndZeroOccupancyPreservePyramidApexAndMarkdownTableWithoutContradiction() throws Exception {
        assertTrue(composer.validClaims(
                json.readTree("[{\"type\":\"INFERENCE\",\"text\":\"【核心结论】对比3档方案：9.5折让利5%，9折让利10%\",\"evidenceIds\":[\"e1\"]}]"),
                Map.of("e1", "折扣95.0%，90.0%")));

        com.zjyz.agent.orch.AgentSkillExecution sandbox = exec("copilot.price_breakeven_sandbox", "AVAILABLE", "COMPLETE",
                "【核心结论】项目「示范工地A」报价沙盘结论（基准日租金 16,438.80 元/天、90 天原价总租金 1,479,492.00 元）：常规让利（95折 + 3天免租）预计总租金 1,358,666.82 元；深度让利（90折 + 7天免租）预计总租金 1,227,978.36 元。");
        Map<String, Object> ps = new java.util.LinkedHashMap<>();
        ps.put("headline", "项目「示范工地A」报价沙盘结论：常规让利（95折 + 3天免租）预计总租金 1,358,666.82 元；深度让利（90折 + 7天免租）预计总租金 1,227,978.36 元。");
        ps.put("markdownTable", "| 方案档位 | 预计总租金(元) |\n| :--- | :--- |\n| 基准方案（原价无免租） | 1,479,492.00 |\n| 常规让利（95折 + 3天免租） | 1,358,666.82 |\n| 深度让利（90折 + 7天免租） | 1,227,978.36 |");
        ps.put("keyPoints", java.util.List.of("**基准测算盘面**：项目「示范工地A」当前日租金基数 16,438.80 元/天。"));
        ps.put("actions", java.util.List.of("商务谈判让利时建议在合同中绑定最低起租天数（95折绑定 98 天、90折绑定 107 天）。"));
        ps.put("boundaryNotes", java.util.List.of("按该项目合同配价表已配置的 3 项材料单价与预计租赁规模核算日租金基数。"));
        sandbox.setCards(java.util.List.of(Map.of("type", "business-records", "data", Map.of("pyramidSummary", ps))));

        com.zjyz.agent.orch.AgentSkillExecution occ = exec("project.material_occupancy", "AVAILABLE", "COMPLETE",
                "截至 2026-10-20，已复核有效单据的实物未归还余额按单位为：无正余额。材料明细共 0 行，当前展示 0 行；负余额异常 0 项。");

        AgentAnalysisComposer.Result res = composer.composeDeterministicPyramid(
                "推演对比原价、95折+3天免租、90折+7天免租三档方案",
                java.util.List.of(sandbox, occ),
                java.util.Collections.emptyList());

        assertTrue(res.success);
        assertTrue(res.answer.startsWith("**分析**\n\n- 【核心结论】"));
        assertTrue(res.answer.contains("| 常规让利（95折 + 3天免租） | 1,358,666.82 |"));
        assertTrue(res.answer.contains("| 深度让利（90折 + 7天免租） | 1,227,978.36 |"));
        assertFalse(res.answer.contains("材料明细共 0 行"));
        assertTrue(res.answer.contains("按该项目合同配价表的预计租赁数量核算日租金基数"));
    }

    private com.zjyz.agent.orch.AgentSkillExecution exec(String code, String avail, String comp, String answer) {
        com.zjyz.agent.orch.AgentSkillExecution ex = new com.zjyz.agent.orch.AgentSkillExecution();
        com.zjyz.agent.model.AgentEvidence ev = new com.zjyz.agent.model.AgentEvidence();
        ev.setToolCode(code);
        ev.setAvailability(avail);
        ev.setCompleteness(comp);
        ex.setEvidence(ev);
        ex.setAnswer(answer);
        return ex;
    }

}
