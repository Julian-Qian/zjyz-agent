package com.zjyz.agent.workspace.v2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.v2.model.AgentV2Models;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentV2ConversationInterpreterTest {

    @Test
    void capabilityQuestionIsResolvedByStructuredModelOutput() {
        StubGateway gateway = new StubGateway(json("CAPABILITY_QUERY", "NEW",
                "了解当前可用能力", "介绍当前权限和范围下的小云能力", null, null));
        AgentV2Models.InterpretationResult result = interpreter(gateway)
                .interpret(context("你目前支持哪些能力", null), scope(), manifest(), "Asia/Shanghai");

        assertTrue(result.isSuccess());
        assertEquals("CAPABILITY_QUERY", result.getInterpretation().getDialogueAct());
        assertEquals("NEW", result.getInterpretation().getRelationType());
        assertEquals("user", gateway.lastMessages.get(1).get("role"));
        assertTrue(gateway.lastMessages.get(1).get("content").toString().startsWith("DATA_ENVELOPE="));
        assertTrue(gateway.lastMessages.get(1).get("content").toString().contains("capabilityManifest"));
    }

    @Test
    void shortFollowUpUsesPreviousTaskInsteadOfAKeywordRouter() {
        StubGateway gateway = new StubGateway(json("BUSINESS_QUERY", "CONTINUE",
                "继续", "继续分析上个月租出材料排行并说明第二名", "上个月", null));
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("taskId", "task-1");
        previous.put("goal", "分析上个月租出材料排行");

        AgentV2Models.InterpretationResult result = interpreter(gateway)
                .interpret(context("然后呢", previous), scope(), manifest(), "Asia/Shanghai");

        assertTrue(result.isSuccess());
        assertEquals("CONTINUE", result.getInterpretation().getRelationType());
        assertEquals("继续分析上个月租出材料排行并说明第二名",
                result.getInterpretation().getTaskSpec().getResolvedGoal());
        assertTrue(gateway.lastMessages.get(1).get("content").toString()
                .contains("分析上个月租出材料排行"));
    }

    @Test
    void timeAndScopeCorrectionProducesResolvedGoalWithoutSelectingATool() {
        StubGateway gateway = new StubGateway(json("BUSINESS_QUERY", "CORRECT",
                "改成上个月且只看当前选择范围", "统计上个月当前冻结范围内的租出材料排行",
                "上个月", "CURRENT_FROZEN_SCOPE"));
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("taskId", "task-2");
        previous.put("goal", "统计本月全部项目的租出材料排行");

        AgentV2Models.InterpretationResult result = interpreter(gateway)
                .interpret(context("改成上个月，只看这些项目", previous), scope(), manifest(), "Asia/Shanghai");

        assertTrue(result.isSuccess());
        assertEquals("CORRECT", result.getInterpretation().getRelationType());
        assertEquals("上个月", result.getInterpretation().getTaskSpec().getTimeRangeExpression());
        assertEquals("CURRENT_FROZEN_SCOPE", result.getInterpretation().getTaskSpec().getScopeIntent());
        assertTrue(result.getInterpretation().getTaskSpec().getCapabilityHints().isEmpty());
        assertTrue(gateway.lastTools.isEmpty());
    }

    @Test
    void unavailableModelExplicitlyDegradesAndDoesNotInventAnInterpretation() {
        StubGateway gateway = new StubGateway("{}");
        gateway.available = false;

        AgentV2Models.InterpretationResult result = interpreter(gateway)
                .interpret(context("查一下项目", null), scope(), manifest(), "Asia/Shanghai");

        assertFalse(result.isSuccess());
        assertEquals("MODEL_UNAVAILABLE", result.getErrorCode());
        assertTrue(result.getWarning().contains("无法连接"));
    }

    @Test
    void currentMonthIsCompleteAndDoesNotAskForASpecificMonthAgain() {
        String response = json("BUSINESS_QUERY", "NEW",
                "这个月有哪些合同到期", "查询这个月到期的合同", "这个月", null)
                .replace("\"missingInputs\":[]", "\"missingInputs\":[\"具体月份\"]");
        StubGateway gateway = new StubGateway(response);

        AgentV2Models.InterpretationResult result = interpreter(gateway)
                .interpret(context("这个月有哪些合同到期", null), scope(), manifest(), "Asia/Shanghai");

        assertTrue(result.isSuccess());
        assertTrue(result.getInterpretation().getTaskSpec().getMissingInputs().isEmpty());
        assertTrue(gateway.lastMessages.get(0).get("content").toString().contains("不能再追问具体日期、月份或年份"));
    }

    @Test
    void preservesIndependentAnalysisTargetAndMoneyMetric() {
        String response = json("BUSINESS_QUERY", "NEW", "哪个材料最赚钱", "今年哪个材料最赚钱", "今年", null)
                .replace("\"expectedOutcome\":\"ANSWER\"",
                        "\"expectedOutcome\":\"CLARIFICATION\",\"analysisTarget\":\"MATERIAL\",\"requestedMetrics\":[\"MONEY_UNSPECIFIED\"]");
        StubGateway gateway = new StubGateway(response);
        AgentV2Models.InterpretationResult result = interpreter(gateway)
                .interpret(context("今年哪个材料最赚钱", null), scope(), manifest(), "Asia/Shanghai");
        assertTrue(result.isSuccess());
        assertEquals("MATERIAL", result.getInterpretation().getTaskSpec().getAnalysisTarget());
        assertEquals(Collections.singletonList("MONEY_UNSPECIFIED"), result.getInterpretation().getTaskSpec().getRequestedMetrics());
        assertTrue(gateway.lastMessages.get(0).get("content").toString().contains("不能把最多当作数量指标"));
    }

    @Test
    void rejectsUnknownStructuredMetricRatherThanSilentlyDowngradingToQuantity() {
        String response = json("BUSINESS_QUERY", "NEW", "材料收益排行", "材料收益排行", null, null)
                .replace("\"expectedOutcome\":\"ANSWER\"",
                        "\"expectedOutcome\":\"ANSWER\",\"requestedMetrics\":[\"GUESS_PROFIT\"]");
        AgentV2Models.InterpretationResult result = interpreter(new StubGateway(response))
                .interpret(context("材料收益排行", null), scope(), manifest(), "Asia/Shanghai");
        assertFalse(result.isSuccess());
    }

    @Test
    void completeOwnerQuestionRecoversFromUnknownAndNeverAsksUserAboutTools() {
        String response = json("UNKNOWN", "NEW", "目前小何在负责哪些项目", "目前小何在负责哪些项目", null, null)
                .replace("\"missingInputs\":[]", "\"missingInputs\":[\"系统是否支持按负责人筛选项目\",\"具体年份\"]");
        AgentV2Models.InterpretationResult result = interpreter(new StubGateway(response))
                .interpret(context("目前小何在负责哪些项目", null), scope(), manifest(), "Asia/Shanghai");
        assertTrue(result.isSuccess());
        assertEquals("BUSINESS_QUERY", result.getInterpretation().getDialogueAct());
        assertEquals("目前小何在负责哪些项目", result.getInterpretation().getTaskSpec().getResolvedGoal());
        assertTrue(result.getInterpretation().getTaskSpec().getMissingInputs().isEmpty());
        assertEquals("READ", result.getInterpretation().getTaskSpec().getRiskLevel());
    }

    @Test
    void answeringOldInvalidCapabilityQuestionRestoresOwnerGoal() {
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("resolvedGoal", "目前小何在负责哪些项目");
        AgentV2Models.BoundedContext context = context("支持", previous);
        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("interactionId", "i-1");
        ui.put("interactionPrompt", "为了继续处理，请补充：系统是否支持按负责人筛选项目。");
        context.setUiContext(ui);
        StubGateway gateway = new StubGateway(json("CLARIFICATION_RESPONSE", "CLARIFICATION_RESPONSE",
                "支持", "支持", null, null));
        AgentV2Models.InterpretationResult result = interpreter(gateway).interpret(context, scope(), manifest(), "Asia/Shanghai");
        assertTrue(result.isSuccess());
        assertEquals("目前小何在负责哪些项目", result.getInterpretation().getTaskSpec().getResolvedGoal());
        assertEquals("CLARIFICATION_RESPONSE", result.getInterpretation().getRelationType());
    }

    @Test
    void stripsCapabilityQuestionButPreservesActualMissingIdentity() {
        String response = json("BUSINESS_QUERY", "NEW", "他负责哪些项目", "他负责哪些项目", null, null)
                .replace("\"missingInputs\":[]", "\"missingInputs\":[\"系统是否支持负责人查询\",\"他指哪位负责人\"]");
        AgentV2Models.InterpretationResult result = interpreter(new StubGateway(response))
                .interpret(context("他负责哪些项目", null), scope(), manifest(), "Asia/Shanghai");
        assertTrue(result.isSuccess());
        assertEquals(Collections.singletonList("他指哪位负责人"), result.getInterpretation().getTaskSpec().getMissingInputs());
    }

    private AgentV2ConversationInterpreter interpreter(StubGateway gateway) {
        return new AgentV2ConversationInterpreter(gateway, new ObjectMapper());
    }

    private AgentV2Models.BoundedContext context(String current, Map<String, Object> previous) {
        AgentV2Models.BoundedContext context = new AgentV2Models.BoundedContext();
        context.setPreviousTask(previous);
        AgentV2Models.ContextMessage message = new AgentV2Models.ContextMessage();
        message.setRole("user");
        message.setContent(current);
        context.setMessages(Collections.singletonList(message));
        context.setMaxCharacters(24000);
        context.setUsedCharacters(current.length());
        return context;
    }

    private AgentV2Models.ScopeSnapshot scope() {
        AgentV2Models.ScopeSnapshot scope = new AgentV2Models.ScopeSnapshot();
        scope.setScopeType("TENANT");
        scope.setRequestedSelectionMode("EXPLICIT");
        scope.setEffectiveSelectionMode("EXPLICIT");
        scope.setProjectIds(Collections.singletonList("project-1"));
        scope.setFrozen(true);
        return scope;
    }

    private Map<String, Object> manifest() {
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("code", "material.transaction_aggregate");
        capability.put("available", true);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("capabilities", Collections.singletonList(capability));
        manifest.put("mode", "READ_ONLY");
        return manifest;
    }

    private String json(String dialogueAct,
                        String relationType,
                        String goal,
                        String resolvedGoal,
                        String timeRange,
                        String scopeIntent) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("goal", goal);
        spec.put("resolvedGoal", resolvedGoal);
        spec.put("expectedOutcome", "ANSWER");
        spec.put("timeRangeExpression", timeRange);
        spec.put("scopeIntent", scopeIntent);
        spec.put("riskLevel", "READ");
        spec.put("referencedEntities", Collections.emptyList());
        spec.put("capabilityHints", Collections.emptyList());
        spec.put("missingInputs", Collections.emptyList());
        spec.put("assumptions", Collections.emptyList());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("dialogueAct", dialogueAct);
        value.put("relationType", relationType);
        value.put("confidence", 0.96d);
        value.put("rationale", "structured test result");
        value.put("taskSpec", spec);
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static class StubGateway implements AgentV2ModelGateway {
        private final String content;
        private boolean available = true;
        private List<Map<String, Object>> lastMessages = new ArrayList<>();
        private List<Map<String, Object>> lastTools = new ArrayList<>();

        private StubGateway(String content) {
            this.content = content;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public AgentModelGateway.ModelResult complete(List<Map<String, Object>> messages,
                                                      List<Map<String, Object>> tools,
                                                      String reasoningEffort) {
            this.lastMessages = messages;
            this.lastTools = tools;
            AgentModelGateway.ModelResult result = new AgentModelGateway.ModelResult();
            result.setSuccess(true);
            result.setProvider("test");
            result.setModel("structured-interpreter");
            result.setContent(content);
            return result;
        }
    }
}
