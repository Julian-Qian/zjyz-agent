package com.zjyz.agent.orch;

import com.zjyz.agent.model.AgentEvidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AgentPlannerService {
    private static final Pattern PROJECT_ID_PATTERN = Pattern.compile("\\b[0-9A-Za-z]{12,}\\b");
    private static final Pattern PROJECT_NO_PATTERN = Pattern.compile("\\b\\d+(?:-[0-9A-Za-z]+)+\\b");

    @Autowired
    private AgentIntentClassifier intentClassifier;
    @Autowired
    private AgentSlotExtractor slotExtractor;
    @Autowired
    private AgentProjectResolver projectResolver;
    @Autowired
    private AgentSkillRegistry skillRegistry;
    @Autowired
    private AgentSessionMemoryStore sessionMemoryStore;

    public AgentSkillExecution planAndExecute(String sessionId, String message, Map<String, Object> context) {
        AgentSessionMemoryStore.MemorySnapshot memory = sessionMemoryStore.get(sessionId);
        AgentIntentType intentType = resolveIntent(message, memory);
        Map<String, Object> mergedContext = mergeContext(context, memory, message);
        AgentSlotBag slots = slotExtractor.extract(message, mergedContext, intentType);

        AgentSkillHandler skillHandler = skillRegistry.resolve(intentType);
        if (skillHandler == null) {
            AgentSkillExecution execution = new AgentSkillExecution();
            execution.setIntent("unknown");
            execution.setConfidence(0.5);
            execution.setAnswer("当前未匹配到可执行技能，请尝试换一种说法。\n例如：统计在租项目、查询库存趋势、检索财务对账单、询问项目管理如何使用，或发起材料预估。");
            execution.setEvidence(buildPlannerEvidence("SkillRegistry", 0));
            return execution;
        }

        if (skillHandler.requiresProjectId(slots) && !StringUtils.hasText(slots.getProjectId())) {
            String resolvedProjectId = projectResolver.resolveProjectId(slots, message);
            if (StringUtils.hasText(resolvedProjectId)) {
                slots.setProjectId(resolvedProjectId);
            }
        }

        if (skillHandler.requiresProjectId(slots) && !StringUtils.hasText(slots.getProjectId())) {
            String clarificationQuestion = "这类查询需要项目信息。请补充项目ID或项目名称，例如：查询测试项目26-1-5的财务对账单。";
            return AgentSkillExecution.clarification(
                    intentType.name().toLowerCase(Locale.ROOT),
                    clarificationQuestion,
                    Collections.singletonList("projectId"),
                    buildPlannerEvidence("Planner", 0)
            );
        }

        return skillHandler.execute(message, mergedContext, slots);
    }

    private AgentIntentType resolveIntent(String message, AgentSessionMemoryStore.MemorySnapshot memory) {
        if (isHelpLocationFollowUp(message, memory)) {
            return AgentIntentType.HELP_KNOWLEDGE;
        }

        AgentIntentType clarificationIntent = resolveClarificationFollowUpIntent(message, memory);
        if (clarificationIntent != null) {
            return clarificationIntent;
        }

        AgentIntentType classifiedIntent = intentClassifier.classify(message);
        if (classifiedIntent != AgentIntentType.PROJECT_SUMMARY) {
            return classifiedIntent;
        }

        AgentIntentType previousIntent = parseIntent(memory == null ? null : memory.getLastIntent());
        if (previousIntent == null) {
            return classifiedIntent;
        }

        if (isFollowUpReference(message)) {
            return previousIntent;
        }
        return classifiedIntent;
    }

    private AgentIntentType resolveClarificationFollowUpIntent(String message, AgentSessionMemoryStore.MemorySnapshot memory) {
        if (memory == null || !memory.isNeedClarification() || !StringUtils.hasText(message)) {
            return null;
        }
        List<String> missingSlots = memory.getMissingSlots();
        if (CollectionUtils.isEmpty(missingSlots)) {
            return null;
        }
        if (missingSlots.contains("projectId")) {
            return hasProjectClue(message) ? parseIntent(memory.getLastIntent()) : null;
        }
        return parseIntent(memory.getLastIntent());
    }

    private boolean hasProjectClue(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.trim();
        if (PROJECT_ID_PATTERN.matcher(normalized).find() || PROJECT_NO_PATTERN.matcher(normalized).find()) {
            return true;
        }
        return containsAny(normalized, "项目", "工程", "标段");
    }

    private Map<String, Object> mergeContext(Map<String, Object> context,
                                             AgentSessionMemoryStore.MemorySnapshot memory,
                                             String message) {
        Map<String, Object> merged = new HashMap<>();
        if (context != null) {
            merged.putAll(context);
        }
        if (memory == null) {
            return merged;
        }

        if (StringUtils.hasText(memory.getLastIntent())) {
            merged.putIfAbsent("memoryLastIntent", memory.getLastIntent());
        }
        if (StringUtils.hasText(memory.getLastUserMessage())) {
            merged.putIfAbsent("memoryLastUserMessage", memory.getLastUserMessage());
        }
        if (StringUtils.hasText(memory.getLastAnswer())) {
            merged.putIfAbsent("memoryLastAnswer", memory.getLastAnswer());
        }
        if (!CollectionUtils.isEmpty(memory.getHelpArticleTitles())) {
            merged.put("memoryHelpArticleTitles", memory.getHelpArticleTitles());
        }
        merged.put("memoryFollowUp", isFollowUpReference(message));
        return merged;
    }

    private AgentIntentType parseIntent(String intent) {
        if (!StringUtils.hasText(intent)) {
            return null;
        }
        String normalized = intent.trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "project_summary":
            case "project-summary":
                return AgentIntentType.PROJECT_SUMMARY;
            case "inventory_summary":
            case "inventory-summary":
                return AgentIntentType.INVENTORY_SUMMARY;
            case "document_search":
            case "document-search":
                return AgentIntentType.DOCUMENT_SEARCH;
            case "project_rent_materials":
            case "project-rent-materials":
                return AgentIntentType.PROJECT_RENT_MATERIALS;
            case "help_knowledge":
            case "help-knowledge":
                return AgentIntentType.HELP_KNOWLEDGE;
            case "material_estimate":
            case "material-estimate":
                return AgentIntentType.MATERIAL_ESTIMATE;
            default:
                return null;
        }
    }

    private boolean isHelpLocationFollowUp(String message, AgentSessionMemoryStore.MemorySnapshot memory) {
        AgentIntentType previousIntent = parseIntent(memory == null ? null : memory.getLastIntent());
        if (previousIntent != AgentIntentType.HELP_KNOWLEDGE) {
            return false;
        }
        return containsAny(message, "文档", "文章", "教程", "指南")
                && containsAny(message, "在哪里", "在哪", "哪里", "入口", "找到", "查看", "看");
    }

    private boolean isFollowUpReference(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.trim();
        if (normalized.length() <= 8 && containsAny(normalized, "继续", "展开", "详细", "细说")) {
            return true;
        }
        return containsAny(normalized, "这个", "这些", "上面", "刚才", "上一条", "那条", "它", "这些文档", "这些文章");
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.length == 0) {
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

    private AgentEvidence buildPlannerEvidence(String skillName, int recordCount) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + LocalDate.now());
        evidence.setSkills(Collections.singletonList(skillName));
        evidence.setApiList(Collections.singletonList("/agent/chat"));
        evidence.setRecordCount(recordCount);
        return evidence;
    }
}
