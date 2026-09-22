package com.zjyz.agent.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.llm.AgentLlmClient;
import com.zjyz.agent.model.*;
import com.zjyz.agent.orch.AgentPlannerService;
import com.zjyz.agent.orch.AgentSessionMemoryStore;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.service.AgentChatService;
import com.zjyz.agent.service.AgentQuotaService;
import com.zjyz.pojo.param.req.QueryDocumentListParam;
import com.zjyz.pojo.param.req.QueryProjectListParam;
import com.zjyz.pojo.param.ret.*;
import com.zjyz.service.ContractService;
import com.zjyz.service.DocumentStrategyFactory;
import com.zjyz.service.InventoryService;
import com.zjyz.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentChatServiceImpl implements AgentChatService {
    private static final DateTimeFormatter TRACE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("\\b[0-9A-Za-z]{12,}\\b");
    private static final Pattern PROJECT_NO_PATTERN = Pattern.compile("\\b\\d+(?:-[0-9A-Za-z]+)+\\b");
    private static final Pattern PROJECT_NAME_PHRASE_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9_-]{2,40}项目[\\u4e00-\\u9fa5A-Za-z0-9_-]{0,20})");
    private static final int DEFAULT_PAGE_SIZE = 10_000;
    private static final String RULE_PROVIDER = "rule-engine";
    private static final String RULE_MODEL = "skills-only";

    @Value("${agent.llm.lowConfidenceThreshold:0.78}")
    private double lowConfidenceThreshold;

    @Value("${agent.llm.answerMaxChars:800}")
    private int answerMaxChars;

    @Autowired
    private AgentLlmClient llmClient;
    @Autowired
    private AgentQuotaService quotaService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ContractService contractService;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private DocumentStrategyFactory documentStrategyFactory;
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentPlannerService plannerService;

    @Autowired
    private AgentSessionMemoryStore sessionMemoryStore;

    @Override
    public AgentChatResponse chat(AgentChatRequest request) {
        String userKey = quotaService.resolveCurrentUserKey();
        String message = request == null || request.getMessage() == null ? "" : request.getMessage().trim();
        String traceId = buildTraceId();
        String sessionId = resolveSessionId(request);

        boolean premiumRequested = llmClient.isPremiumConfigured();
        AgentQuotaDecision quotaDecision = quotaService.preCheck(userKey, message, premiumRequested);
        if (!quotaDecision.isAllow()) {
            AgentChatResponse response = new AgentChatResponse();
            response.setSessionId(sessionId);
            response.setTraceId(traceId);
            response.setIntent("quota_blocked");
            response.setConfidence(1.0);
            response.setProvider(RULE_PROVIDER);
            response.setModel("quota-guard");
            response.setDegradeMode(true);
            response.setAnswer("本月智能问答额度已用完，已切换为固定报表模式。");
            response.setWarnings(Collections.singletonList(quotaDecision.getReason()));
            response.setQuota(quotaDecision.getQuota());
            response.setEvidence(buildEvidence(
                    "asOf=" + LocalDate.now(),
                    Collections.singletonList("QuotaGuard"),
                    Collections.singletonList("/agent/chat"),
                    0
            ));
            return response;
        }

        AgentSkillExecution execution = plannerService.planAndExecute(sessionId, message, request == null ? null : request.getContext());
        List<String> warnings = new ArrayList<>();
        if (!CollectionUtils.isEmpty(execution.getWarnings())) {
            warnings.addAll(execution.getWarnings());
        }

        String finalAnswer = StringUtils.hasText(execution.getAnswer())
                ? execution.getAnswer()
                : execution.getClarificationQuestion();
        if (!StringUtils.hasText(finalAnswer)) {
            finalAnswer = "当前暂无可返回内容";
        }
        double finalConfidence = execution.getConfidence() == null ? 0.86 : execution.getConfidence();
        String provider = RULE_PROVIDER;
        String model = RULE_MODEL;
        boolean degradeMode = false;
        boolean premiumUsed = false;

        int totalPromptTokens = Math.max(quotaDecision.getEstimatedPromptTokens(), 0);
        int totalCompletionTokens = Math.min(quotaService.getRequestMaxOutputTokens(), estimateCompletionTokens(finalAnswer));
        double totalCostCny = 0d;

        boolean needClarification = Boolean.TRUE.equals(execution.getNeedClarification());
        if (!needClarification) {
            LlmFlowResult llmFlowResult = runLlmWithFallback(message, execution, quotaDecision, traceId);
            if (!CollectionUtils.isEmpty(llmFlowResult.warnings)) {
                warnings.addAll(llmFlowResult.warnings);
            }

            if (llmFlowResult.selectedResult != null && llmFlowResult.selectedResult.isSuccess()) {
                AgentLlmCallResult selected = llmFlowResult.selectedResult;
                if (StringUtils.hasText(selected.getAnswer())) {
                    finalAnswer = selected.getAnswer();
                }
                if (selected.getConfidence() != null) {
                    finalConfidence = selected.getConfidence();
                }
                provider = selected.getProvider();
                model = selected.getModel();
                premiumUsed = llmFlowResult.premiumUsed;
                totalPromptTokens = llmFlowResult.totalPromptTokens > 0 ? llmFlowResult.totalPromptTokens : totalPromptTokens;
                totalCompletionTokens = llmFlowResult.totalCompletionTokens > 0 ? llmFlowResult.totalCompletionTokens : totalCompletionTokens;
                totalCostCny = llmFlowResult.totalCostCny > 0 ? llmFlowResult.totalCostCny
                        : quotaService.estimateCostCny(model, totalPromptTokens, totalCompletionTokens);
            } else if (llmClient.isEnabled()) {
                degradeMode = true;
                warnings.add("模型服务不可用，已返回基于Skills查询的结构化结果。");
            }
        } else {
            model = "planner-clarification";
        }

        quotaService.recordUsage(userKey, totalPromptTokens, totalCompletionTokens, premiumUsed, totalCostCny);

        AgentChatResponse response = new AgentChatResponse();
        response.setSessionId(sessionId);
        response.setTraceId(traceId);
        response.setIntent(execution.getIntent());
        response.setConfidence(clampConfidence(finalConfidence));
        response.setProvider(provider);
        response.setModel(model);
        response.setAnswer(trimAnswer(finalAnswer));
        response.setCards(execution.getCards());
        response.setWarnings(deduplicateWarnings(warnings));
        response.setEvidence(execution.getEvidence());
        response.setNeedClarification(needClarification);
        response.setClarificationQuestion(execution.getClarificationQuestion());
        response.setMissingSlots(execution.getMissingSlots());
        response.setDegradeMode(degradeMode);
        response.setQuota(quotaService.getQuota(userKey));
        sessionMemoryStore.save(sessionId, message, execution, finalAnswer);
        return response;
    }

    private LlmFlowResult runLlmWithFallback(String message, AgentSkillExecution execution,
                                             AgentQuotaDecision quotaDecision, String traceId) {
        LlmFlowResult result = new LlmFlowResult();
        if (!llmClient.isEnabled()) {
            return result;
        }

        String systemPrompt;
        String userPrompt;
        try {
            String factsJson = buildFactsJson(execution);
            systemPrompt = buildSystemPrompt();
            userPrompt = buildUserPrompt(message, factsJson, traceId);
        } catch (Exception e) {
            log.warn("runLlmWithFallback build prompt error err={}", e.getMessage());
            result.warnings.add("响应解析失败");
            return result;
        }

        if (isMaterialEstimateIntent(execution)) {
            if (!llmClient.isDoubaoAvailable()) {
                result.warnings.add("豆包 API Key 未配置，跳过模型润色。");
                return result;
            }
            AgentLlmCallResult doubao = invokeChatCompletion(AgentLlmClient.PROVIDER_DOUBAO, systemPrompt, userPrompt, execution);
            accumulateUsage(result, doubao);
            if (doubao.isSuccess()) {
                result.selectedResult = doubao;
            } else {
                result.warnings.add("豆包调用失败：" + (StringUtils.hasText(doubao.getWarning()) ? doubao.getWarning() : "未知错误"));
            }
            return result;
        }
        if (!llmClient.isDeepseekConfigured()) {
            result.warnings.add("DeepSeek API Key 未配置，跳过模型润色。");
            return result;
        }

        AgentLlmCallResult primary = invokeChatCompletion(AgentLlmClient.PROVIDER_DEEPSEEK, systemPrompt, userPrompt, execution);
        accumulateUsage(result, primary);

        if (primary.isSuccess()) {
            result.selectedResult = primary;
            boolean lowConfidence = primary.getConfidence() == null || primary.getConfidence() < lowConfidenceThreshold;
            if (!lowConfidence) {
                return result;
            }
            result.warnings.add("DeepSeek 置信度较低，触发回退策略。");
            if (!quotaDecision.isPremiumAllowed()) {
                result.warnings.add("高阶模型月额度已用完，保留 DeepSeek 结果。");
                return result;
            }
            if (!llmClient.isFallbackAvailable()) {
                result.warnings.add("未配置回退模型，保留 DeepSeek 结果。");
                return result;
            }

            AgentLlmCallResult fallback = invokeChatCompletion(AgentLlmClient.PROVIDER_OPENAI, systemPrompt, userPrompt, execution);
            accumulateUsage(result, fallback);
            if (fallback.isSuccess()) {
                result.selectedResult = fallback;
                result.premiumUsed = true;
            } else {
                result.warnings.add("回退模型调用失败，保留 DeepSeek 结果。");
            }
            return result;
        }

        result.warnings.add("DeepSeek 调用失败：" + (StringUtils.hasText(primary.getWarning()) ? primary.getWarning() : "未知错误"));
        if (!quotaDecision.isPremiumAllowed()) {
            result.warnings.add("高阶模型月额度已用完，无法执行回退模型。");
            return result;
        }
        if (!llmClient.isFallbackAvailable()) {
            result.warnings.add("未配置回退模型，无法执行回退。");
            return result;
        }

        AgentLlmCallResult fallback = invokeChatCompletion(AgentLlmClient.PROVIDER_OPENAI, systemPrompt, userPrompt, execution);
        accumulateUsage(result, fallback);
        if (fallback.isSuccess()) {
            result.selectedResult = fallback;
            result.premiumUsed = true;
        } else {
            result.warnings.add("回退模型调用失败：" +
                    (StringUtils.hasText(fallback.getWarning()) ? fallback.getWarning() : "未知错误"));
        }
        return result;
    }

    /**
     * 调用 AgentLlmClient 并保持旧版 chat 行为：答案按 answerMaxChars 截断、
     * 模型未返回置信度时回填技能执行置信度。
     */
    private AgentLlmCallResult invokeChatCompletion(String provider, String systemPrompt, String userPrompt,
                                                    AgentSkillExecution execution) {
        AgentLlmCallResult result = llmClient.chatCompletion(provider, systemPrompt, userPrompt, null);
        if (result.isSuccess()) {
            result.setAnswer(trimAnswer(result.getAnswer()));
            result.setConfidence(result.getConfidence() != null
                    ? clampConfidence(result.getConfidence())
                    : execution.getConfidence());
        }
        return result;
    }

    private String buildFactsJson(AgentSkillExecution execution) throws JsonProcessingException {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("intent", execution.getIntent());
        facts.put("draftAnswer", execution.getAnswer());
        facts.put("cards", execution.getCards());
        facts.put("evidence", execution.getEvidence());
        facts.put("warnings", execution.getWarnings());
        return objectMapper.writeValueAsString(facts);
    }

    private String buildSystemPrompt() {
        return "你是智建云租的智能助手小云。你必须且只能依据给定FACTS_JSON回答，不得编造数字或单据。" +
                "严格输出JSON对象，格式：{\"answer\":\"...\",\"confidence\":0.0}。" +
                "answer要求：中文、先结论后证据、简洁明确；优先复述draftAnswer中的结论和明细。" +
                "如果cards中存在overdueProjects且非空，必须列出前5个项目名称与截止日期；仅当draftAnswer明确无法判断时才说“数据不足”。" +
                "当intent=help_knowledge时，必须引用cards.articles中的文档标题作为参考，不得生成cards中不存在的功能步骤。" +
                "confidence范围[0,1]。";
    }

    private String buildUserPrompt(String userMessage, String factsJson, String traceId) {
        return "用户问题:\n" + userMessage + "\n\n" +
                "FACTS_JSON:\n" + factsJson + "\n\n" +
                "请仅根据FACTS_JSON生成回答，不得添加未出现的业务数字。traceId=" + traceId;
    }

    private boolean isMaterialEstimateIntent(AgentSkillExecution execution) {
        return execution != null && "material_estimate".equalsIgnoreCase(execution.getIntent());
    }

    private void accumulateUsage(LlmFlowResult aggregate, AgentLlmCallResult callResult) {
        if (callResult == null) {
            return;
        }
        int promptTokens = callResult.getPromptTokens() == null ? 0 : Math.max(callResult.getPromptTokens(), 0);
        int completionTokens = callResult.getCompletionTokens() == null ? 0 : Math.max(callResult.getCompletionTokens(), 0);
        aggregate.totalPromptTokens += promptTokens;
        aggregate.totalCompletionTokens += completionTokens;
        if (StringUtils.hasText(callResult.getModel()) && (promptTokens > 0 || completionTokens > 0)) {
            aggregate.totalCostCny += quotaService.estimateCostCny(callResult.getModel(), promptTokens, completionTokens);
        }
    }

    private List<String> deduplicateWarnings(List<String> warnings) {
        if (CollectionUtils.isEmpty(warnings)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String warning : warnings) {
            if (StringUtils.hasText(warning)) {
                set.add(warning.trim());
            }
        }
        return new ArrayList<>(set);
    }

    private double clampConfidence(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private IntentExecution executeIntent(String message, Map<String, Object> context) {
        if (containsAny(message, "下载", "单据", "导出", "附件", "采购", "出库", "入库", "归还单", "租出单", "租入单", "退租单", "对账", "结算", "财务")) {
            return executeDocumentIntent(message, context);
        }
        if (containsAny(message, "库存", "材料", "趋势", "下降", "六个月", "半年")) {
            return executeInventoryIntent(message);
        }
        return executeProjectIntent(message, context);
    }

    private IntentExecution executeProjectIntent(String message, Map<String, Object> context) {
        String businessType = resolveProjectBusinessType(message, context);
        ProjectListRet ongoingRet = queryProjectList("0", businessType, null);
        ProjectListRet completedRet = queryProjectList("1", businessType, null);
        int ongoing = (int) (ongoingRet.getTotalNum() == null ? 0 : ongoingRet.getTotalNum());
        int completed = (int) (completedRet.getTotalNum() == null ? 0 : completedRet.getTotalNum());
        int total = ongoing + completed;

        StringBuilder answer = new StringBuilder();
        answer.append("当前").append("rent_in".equals(businessType) ? "租入管理" : "租出管理")
                .append("项目共 ").append(total)
                .append(" 个，其中进行中 ").append(ongoing)
                .append(" 个，已完成 ").append(completed).append(" 个。");

        List<String> warnings = new ArrayList<>();
        List<OverdueProjectInfo> overdueProjects = Collections.emptyList();
        int unknownEndDateCount = 0;
        if (containsAny(message, "超期", "逾期", "预定回收", "回收时间", "截止日", "截止日期", "到期")) {
            OverdueCheckResult overdueCheckResult = detectOverdueProjects(ongoingRet.getProjectBriefInfos());
            overdueProjects = overdueCheckResult.overdueProjects;
            unknownEndDateCount = overdueCheckResult.unknownEndDateCount;
            answer.append(" 疑似超期项目 ").append(overdueProjects.size()).append(" 个。");
            if (!CollectionUtils.isEmpty(overdueProjects)) {
                String names = overdueProjects.stream()
                        .limit(5)
                        .map(item -> item.projectName + "(截止" + item.contractEndDate + ")")
                        .collect(Collectors.joining("、"));
                answer.append(" 示例：").append(names).append("。");
            } else {
                warnings.add("未发现疑似超期项目。");
            }
            if (unknownEndDateCount > 0) {
                warnings.add("有 " + unknownEndDateCount + " 个进行中项目缺少合同截止日期，无法参与超期判断。");
            }
        }

        Map<String, Object> projectSummaryCard = new LinkedHashMap<>();
        projectSummaryCard.put("type", "project-summary");
        projectSummaryCard.put("businessType", businessType);
        projectSummaryCard.put("total", total);
        projectSummaryCard.put("ongoing", ongoing);
        projectSummaryCard.put("completed", completed);
        projectSummaryCard.put("overdueCount", overdueProjects.size());
        projectSummaryCard.put("overdueProjects", overdueProjects.stream().limit(20).collect(Collectors.toList()));
        projectSummaryCard.put("unknownEndDateCount", unknownEndDateCount);
        List<Map<String, Object>> cards = Collections.singletonList(projectSummaryCard);

        AgentEvidence evidence = buildEvidence(
                "asOf=" + LocalDate.now(),
                Collections.singletonList("ProjectRentInsightSkill"),
                Arrays.asList("/project/queryProjectList", "/contract/queryContract"),
                total
        );
        return new IntentExecution("project_summary", 0.90, answer.toString(), cards, warnings, evidence);
    }

    private IntentExecution executeInventoryIntent(String message) {
        List<MaterialInventoryRet> inventoryList = inventoryService.queryInventory();
        if (CollectionUtils.isEmpty(inventoryList)) {
            AgentEvidence evidence = buildEvidence(
                    "asOf=" + LocalDate.now(),
                    Collections.singletonList("InventorySnapshotSkill"),
                    Collections.singletonList("/document/query-inventory"),
                    0
            );
            return new IntentExecution("inventory_snapshot", 0.88, "当前未查询到库存数据。", Collections.emptyList(),
                    Collections.emptyList(), evidence);
        }

        List<MaterialInventoryRet> lowStock = new ArrayList<>(inventoryList);
        lowStock.sort(Comparator.comparingInt(item -> item.getInventoryQuantity() == null ? Integer.MAX_VALUE : item.getInventoryQuantity()));
        List<MaterialInventoryRet> lowStockTop = lowStock.stream().limit(5).collect(Collectors.toList());

        String lowStockText = lowStockTop.stream()
                .map(item -> item.getMaterialName() + "(" + safeInt(item.getInventoryQuantity()) + ")")
                .collect(Collectors.joining("、"));
        StringBuilder answer = new StringBuilder("当前库存材料共 ")
                .append(inventoryList.size())
                .append(" 种。库存较低材料示例：")
                .append(lowStockText)
                .append("。");

        List<String> warnings = new ArrayList<>();
        if (containsAny(message, "趋势", "下降", "六个月", "半年")) {
            List<InventoryDropInfo> drops = calculateInventoryDrops(inventoryList);
            if (CollectionUtils.isEmpty(drops)) {
                warnings.add("未找到可计算6个月趋势的材料流水数据。");
            } else {
                String dropText = drops.stream()
                        .limit(3)
                        .map(item -> item.materialName + "(" + item.delta + ")")
                        .collect(Collectors.joining("、"));
                answer.append(" 近6个月下降较快材料：").append(dropText).append("。");
            }
        }

        List<Map<String, Object>> cards = Collections.singletonList(new LinkedHashMap<String, Object>() {{
            put("type", "inventory-summary");
            put("materialCount", inventoryList.size());
            put("lowStockTop", lowStockTop);
        }});

        AgentEvidence evidence = buildEvidence(
                "range=" + LocalDate.now().minusMonths(6) + "~" + LocalDate.now(),
                Collections.singletonList("InventoryTrendSkill"),
                Arrays.asList("/document/query-inventory", "/document/query-inventory-detail"),
                inventoryList.size()
        );
        return new IntentExecution("inventory_summary", 0.88, answer.toString(), cards, warnings, evidence);
    }

    private IntentExecution executeDocumentIntent(String message, Map<String, Object> context) {
        String queryType = resolveQueryType(message);
        String projectId = resolveProjectId(message, context);
        if (typeNeedsProject(queryType) && !StringUtils.hasText(projectId)) {
            projectId = resolveProjectIdByKeyword(message, context);
        }
        if (typeNeedsProject(queryType) && !StringUtils.hasText(projectId)) {
            AgentEvidence evidence = buildEvidence(
                    "asOf=" + LocalDate.now(),
                    Collections.singletonList("DocumentLocatorSkill"),
                    Collections.singletonList("/document/queryDocumentList"),
                    0
            );
            return new IntentExecution(
                    "document_search",
                    0.85,
                    "当前问题缺少项目ID。请补充项目ID后我可以精确查询单据并给出下载入口。",
                    Collections.emptyList(),
                    Collections.singletonList("项目类单据检索建议传入 projectId"),
                    evidence
            );
        }

        QueryDocumentListParam queryParam = new QueryDocumentListParam();
        queryParam.setPageNum(1);
        queryParam.setPageSize(10);
        queryParam.setQueryType(queryType);
        queryParam.setProjectId(projectId);
        DocumentListInfoRet listInfo = documentStrategyFactory.queryDocumentList(queryParam);
        if (listInfo == null || CollectionUtils.isEmpty(listInfo.getDocumentBriefInfoList())) {
            AgentEvidence evidence = buildEvidence(
                    "asOf=" + LocalDate.now(),
                    Collections.singletonList("DocumentLocatorSkill"),
                    Collections.singletonList("/document/queryDocumentList"),
                    0
            );
            return new IntentExecution("document_search", 0.86, "未查询到匹配单据。", Collections.emptyList(),
                    Collections.emptyList(), evidence);
        }

        List<DocumentBriefInfo> briefInfos = listInfo.getDocumentBriefInfoList();
        if (CollectionUtils.isEmpty(briefInfos)) {
            AgentEvidence evidence = buildEvidence(
                    "asOf=" + LocalDate.now(),
                    Collections.singletonList("DocumentLocatorSkill"),
                    Collections.singletonList("/document/queryDocumentList"),
                    0
            );
            return new IntentExecution("document_search", 0.86, "未查询到匹配单据。", Collections.emptyList(),
                    Collections.emptyList(), evidence);
        }

        String names = briefInfos.stream()
                .limit(3)
                .map(item -> item.getDocumentName() + "(" + item.getDocumentId() + ")")
                .collect(Collectors.joining("、"));
        String downloadHint = buildDownloadHint(queryType, briefInfos.get(0).getDocumentId());
        String answer = "已找到 " + briefInfos.size() + " 条单据，示例：" + names + "。下载方式：" + downloadHint;

        List<Map<String, Object>> cards = Collections.singletonList(new LinkedHashMap<String, Object>() {{
            put("type", "document-list");
            put("queryType", queryType);
            put("count", briefInfos.size());
            put("items", briefInfos);
        }});

        AgentEvidence evidence = buildEvidence(
                "asOf=" + LocalDate.now(),
                Collections.singletonList("DocumentLocatorSkill"),
                Collections.singletonList("/document/queryDocumentList"),
                briefInfos.size()
        );
        return new IntentExecution("document_search", 0.87, answer, cards, Collections.emptyList(), evidence);
    }

    private OverdueCheckResult detectOverdueProjects(List<ProjectListRet.ProjectBriefInfo> projects) {
        OverdueCheckResult result = new OverdueCheckResult();
        if (CollectionUtils.isEmpty(projects)) {
            return result;
        }
        LocalDate today = LocalDate.now();
        for (ProjectListRet.ProjectBriefInfo project : projects) {
            ContractInfoRet contractInfo = contractService.queryContract(project.getProjectId());
            LocalDate endDate = parseDate(contractInfo == null ? null : contractInfo.getEndDate());
            if (endDate == null) {
                result.unknownEndDateCount += 1;
                continue;
            }
            if (endDate.isBefore(today)) {
                OverdueProjectInfo info = new OverdueProjectInfo();
                info.projectId = project.getProjectId();
                info.projectName = StringUtils.hasText(project.getProjectName()) ? project.getProjectName() : project.getProjectId();
                info.contractEndDate = endDate.toString();
                info.overdueDays = (int) (today.toEpochDay() - endDate.toEpochDay());
                result.overdueProjects.add(info);
            }
        }
        return result;
    }

    private List<InventoryDropInfo> calculateInventoryDrops(List<MaterialInventoryRet> inventoryList) {
        LocalDate startDate = LocalDate.now().minusMonths(6);
        List<InventoryDropInfo> drops = new ArrayList<>();

        for (MaterialInventoryRet material : inventoryList) {
            List<MaterialInventoryDetailRet> detailList = inventoryService.queryInventoryDetail(material.getMaterialId());
            if (CollectionUtils.isEmpty(detailList)) {
                continue;
            }

            List<MaterialInventoryDetailRet> inRange = detailList.stream()
                    .filter(item -> {
                        LocalDate date = parseDate(item.getDocumentCreateDate());
                        return date != null && !date.isBefore(startDate) && !date.isAfter(LocalDate.now());
                    })
                    .sorted(Comparator.comparing(item -> parseDate(item.getDocumentCreateDate())))
                    .collect(Collectors.toList());

            if (inRange.size() < 2) {
                continue;
            }

            long first = inRange.get(0).getInventoryQuantity();
            long last = inRange.get(inRange.size() - 1).getInventoryQuantity();
            long delta = last - first;
            if (delta < 0) {
                drops.add(new InventoryDropInfo(material.getMaterialName(), delta));
            }
        }
        drops.sort(Comparator.comparingLong(item -> item.delta));
        return drops;
    }

    private ProjectListRet queryProjectList(String statusFlag, String businessType, String keyword) {
        QueryProjectListParam param = new QueryProjectListParam();
        param.setPageNum(1);
        param.setPageSize(DEFAULT_PAGE_SIZE);
        param.setProjectStatusFlag(statusFlag);
        param.setProjectBusinessType(businessType);
        param.setKeyword(keyword);
        ProjectListRet ret = projectService.queryProjectList(param);
        if (ret == null) {
            ret = new ProjectListRet();
            ret.setCurrent(1L);
            ret.setTotalNum(0L);
            ret.setProjectBriefInfos(Collections.emptyList());
            return ret;
        }
        if (ret.getProjectBriefInfos() == null) {
            ret.setProjectBriefInfos(Collections.emptyList());
        }
        return ret;
    }

    private String resolveProjectBusinessType(String message, Map<String, Object> context) {
        Object contextBusinessType = context == null ? null : context.get("projectBusinessType");
        if (contextBusinessType != null && "rent_in".equalsIgnoreCase(String.valueOf(contextBusinessType))) {
            return "rent_in";
        }
        if (containsAny(message, "租入", "退租", "rent in", "rent_in")) {
            return "rent_in";
        }
        return "rent_out";
    }

    private String resolveProjectId(String message, Map<String, Object> context) {
        if (context != null && context.get("projectId") != null) {
            String value = String.valueOf(context.get("projectId"));
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        if (!StringUtils.hasText(message)) {
            return null;
        }
        Matcher matcher = PROJECT_ID_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private String resolveProjectIdByKeyword(String message, Map<String, Object> context) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        String normalizedMessage = normalizeForMatch(message);
        String businessType = resolveProjectBusinessType(message, context);

        List<ProjectListRet.ProjectBriefInfo> candidates = new ArrayList<>();
        candidates.addAll(queryProjectList("0", businessType, null).getProjectBriefInfos());
        candidates.addAll(queryProjectList("1", businessType, null).getProjectBriefInfos());

        ProjectListRet.ProjectBriefInfo bestMatch = null;
        int bestLength = -1;
        for (ProjectListRet.ProjectBriefInfo info : candidates) {
            String projectName = info == null ? null : info.getProjectName();
            if (!StringUtils.hasText(projectName)) {
                continue;
            }
            String normalizedName = normalizeForMatch(projectName);
            if (!StringUtils.hasText(normalizedName) || !normalizedMessage.contains(normalizedName)) {
                continue;
            }
            if (normalizedName.length() > bestLength) {
                bestMatch = info;
                bestLength = normalizedName.length();
            }
        }
        if (bestMatch != null && StringUtils.hasText(bestMatch.getProjectId())) {
            return bestMatch.getProjectId();
        }

        String keyword = extractProjectKeyword(message);
        if (!StringUtils.hasText(keyword)) {
            return null;
        }

        ProjectListRet ongoingByKeyword = queryProjectList("0", businessType, keyword);
        if (!CollectionUtils.isEmpty(ongoingByKeyword.getProjectBriefInfos())) {
            return ongoingByKeyword.getProjectBriefInfos().get(0).getProjectId();
        }
        ProjectListRet completedByKeyword = queryProjectList("1", businessType, keyword);
        if (!CollectionUtils.isEmpty(completedByKeyword.getProjectBriefInfos())) {
            return completedByKeyword.getProjectBriefInfos().get(0).getProjectId();
        }
        return null;
    }

    private String normalizeForMatch(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            boolean keep = Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || (ch >= '\u4e00' && ch <= '\u9fa5');
            if (keep) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private String extractProjectKeyword(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        Matcher projectNoMatcher = PROJECT_NO_PATTERN.matcher(message);
        if (projectNoMatcher.find()) {
            return projectNoMatcher.group();
        }
        Matcher projectNameMatcher = PROJECT_NAME_PHRASE_PATTERN.matcher(message);
        if (projectNameMatcher.find()) {
            return projectNameMatcher.group(1);
        }
        return null;
    }
    private String resolveQueryType(String message) {
        if (containsAny(message, "归还")) {
            return "1";
        }
        if (containsAny(message, "赔偿")) {
            return "2";
        }
        if (containsAny(message, "采购")) {
            return "5";
        }
        if (containsAny(message, "其他入库", "入库")) {
            return "6";
        }
        if (containsAny(message, "出库")) {
            return "7";
        }
        if (containsAny(message, "物料对账")) {
            return "8";
        }
        if (containsAny(message, "财务对账", "结算")) {
            return "3";
        }
        if (containsAny(message, "租入")) {
            return "9";
        }
        if (containsAny(message, "退租")) {
            return "10";
        }
        return "0";
    }

    private boolean typeNeedsProject(String queryType) {
        return Arrays.asList("0", "1", "2", "3", "8", "9", "10").contains(queryType);
    }

    private String buildDownloadHint(String queryType, String documentId) {
        switch (queryType) {
            case "0":
                return "/document/exportRentDocumentExcel?rentDocumentId=" + documentId;
            case "1":
                return "/document/exportReturnDocumentExcel?returnDocumentId=" + documentId;
            case "2":
                return "/document/exportCompensationDocumentExcel?compensationDocumentId=" + documentId;
            case "3":
                return "/settlementDocument/exportSettlementDocumentExcel?documentId=" + documentId;
            case "8":
                return "/document/exportReconciliationDocumentExcel?reconciliationDocumentId=" + documentId;
            default:
                return "可通过单据详情中的附件 fileId 调用 /document/downloadFile 下载";
        }
    }

    private AgentEvidence buildEvidence(String timeRange, List<String> skills, List<String> apis, int recordCount) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange(timeRange);
        evidence.setSkills(skills);
        evidence.setApiList(apis);
        evidence.setRecordCount(recordCount);
        return evidence;
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private LocalDate parseDate(String dateStr) {
        if (!StringUtils.hasText(dateStr)) {
            return null;
        }
        String normalized = dateStr.trim();
        try {
            if (normalized.length() >= 10) {
                normalized = normalized.substring(0, 10);
            }
            return LocalDate.parse(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    private int estimateCompletionTokens(String answer) {
        if (!StringUtils.hasText(answer)) {
            return 0;
        }
        return (int) Math.ceil(answer.length() * 1.2);
    }

    private String trimAnswer(String answer) {
        if (!StringUtils.hasText(answer)) {
            return "当前暂无可返回内容。";
        }
        return answer.length() > answerMaxChars ? answer.substring(0, answerMaxChars) + "..." : answer;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String buildTraceId() {
        return "agt_" + LocalDateTime.now().format(TRACE_TS);
    }

    private String resolveSessionId(AgentChatRequest request) {
        if (request != null && StringUtils.hasText(request.getSessionId())) {
            return request.getSessionId();
        }
        return "sess_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static class IntentExecution {
        private final String intent;
        private final double confidence;
        private final String answer;
        private final List<Map<String, Object>> cards;
        private final List<String> warnings;
        private final AgentEvidence evidence;

        private IntentExecution(String intent, double confidence, String answer, List<Map<String, Object>> cards,
                                List<String> warnings, AgentEvidence evidence) {
            this.intent = intent;
            this.confidence = confidence;
            this.answer = answer;
            this.cards = cards;
            this.warnings = warnings;
            this.evidence = evidence;
        }
    }

    private static class InventoryDropInfo {
        private final String materialName;
        private final long delta;

        private InventoryDropInfo(String materialName, long delta) {
            this.materialName = materialName;
            this.delta = delta;
        }
    }

    private static class OverdueProjectInfo {
        private String projectId;
        private String projectName;
        private String contractEndDate;
        private Integer overdueDays;
    }

    private static class OverdueCheckResult {
        private final List<OverdueProjectInfo> overdueProjects = new ArrayList<>();
        private int unknownEndDateCount;
    }

    private static class LlmFlowResult {
        private AgentLlmCallResult selectedResult;
        private final List<String> warnings = new ArrayList<>();
        private int totalPromptTokens;
        private int totalCompletionTokens;
        private double totalCostCny;
        private boolean premiumUsed;
    }
}
