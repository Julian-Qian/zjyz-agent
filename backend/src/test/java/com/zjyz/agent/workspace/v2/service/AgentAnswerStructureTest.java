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
}
