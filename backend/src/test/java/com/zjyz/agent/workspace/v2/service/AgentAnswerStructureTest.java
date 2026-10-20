package com.zjyz.agent.workspace.v2.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentAnswerStructureTest {
    @Test void simpleAnswerAndExistingStepsStayIntact() {
        for(String text:List.of("共 2 个项目。", "1. 填写名称。\n2. 核对后保存。", "| 金额 | 数量 |\n| 120.50 | 3 |", "```\n原文。不能切开。\n```"))
            assertEquals(text,AgentAnswerStructure.present(text));
    }
    @Test void longQueryLeadsWithResultAndKeepsEveryFactAndLimitation() {
        String text="当前范围内共有 3 个项目需要核对，未归还材料数量为 120 根、30 个，统计截止日期为 2026-10-10。金额按正式结算记录计算，共 1234.56 元，不代表银行实际到账金额，也不能据此判断企业利润。部分历史记录没有完整明细，因此只能核对当前提供的数据，无法还原所有历史变化。";
        String answer=AgentAnswerStructure.present(text);
        assertTrue(answer.startsWith(text.substring(0,text.indexOf('。')+1)));
        assertTrue(answer.contains("\n\n- "));
        assertEquals(text.replaceAll("\\s", ""),answer.replace("- ","").replaceAll("\\s", ""));
    }
    @Test void optionTitlesDoNotRepeatParentAndConditionsRemain() {
        List<Map<?,?>> refs=List.of(
            Map.of("title","合同｜概览","quote","合同包含三个独立选项。","source","PUBLISHED_HELP"),
            Map.of("title","合同｜选项甲","quote","# 选项甲\n\n所属功能：合同。\n\n只有有权限时可用。\n\n1. 核对字段。\n2. 保存。","source","PUBLISHED_HELP"),
            Map.of("title","合同｜选项乙","quote","# 选项乙\n\n另一种含义，不自动付款。","source","PUBLISHED_HELP"));
        String answer=AgentGuidanceAnswerPresenter.present(refs);
        assertTrue(answer.startsWith("合同包含三个独立选项。"));
        assertFalse(answer.contains("合同｜"));assertFalse(answer.contains("所属功能"));
        assertTrue(answer.contains("**1. 选项甲**"));assertTrue(answer.contains("只有有权限时可用。"));
        assertTrue(answer.contains("1. 核对字段。\n2. 保存。"));assertTrue(answer.contains("不自动付款"));
        assertEquals(1,answer.split("来源：",-1).length-1);
        assertTrue(refs.get(1).get("quote").toString().contains("所属功能"));
    }

    @Test void returnScenarioGuidancePresentsPyramidConclusionFirstThenNumberedScenarios() {
        List<Map<?,?>> refs=List.of(
            Map.of("title","归还单：新建、编辑、复核与导出",
                "quote","### 归还规则总览\n\n在智建云租中，归还单按“项目 + 材料规格”累计核算在租余量（不绑定单张租出单），因此同一批租出材料支持分多次新建归还单分批归还；若发生多归还材料（超过当前在租数量）或归还不在租出单里的材料（归还未租出材料），系统允许先保存草稿，但会自动标记为多还异常并拦截直接复核，需核对历史收发、填写多还原因并完成异常确认后方可复核入账。\n\n### 材料分多次归还（分批归还规则）\n\n同一张租出单或同一项目的材料可以分多次归还。\n\n### 多归还了材料（超过当前在租数量）\n\n当本次填写的归还数量超过该项目下该材料当前的剩余在租数量时，系统会将超出部分识别为“同规格超量多还”。\n\n### 归还了不在租出单里的材料（归还未租出材料）\n\n当归还的材料在该项目下没有任何历史租出单记录时，系统会将其识别为“归还未租出材料（无原租出记录）”。",
                "source","PUBLISHED_HELP"));
        String answer=AgentGuidanceAnswerPresenter.present(refs);
        assertTrue(answer.startsWith("在智建云租中，归还单按“项目 + 材料规格”累计核算在租余量"));
        assertTrue(answer.contains("**1. 材料分多次归还（分批归还规则）**"));
        assertTrue(answer.contains("**2. 多归还了材料（超过当前在租数量）**"));
        assertTrue(answer.contains("**3. 归还了不在租出单里的材料（归还未租出材料）**"));
    }
}
