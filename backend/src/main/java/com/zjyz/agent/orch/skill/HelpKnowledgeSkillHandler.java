package com.zjyz.agent.orch.skill;

import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentIntentType;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.orch.AgentSkillHandler;
import com.zjyz.agent.orch.AgentSlotBag;
import com.zjyz.pojo.param.ret.HelpArticleBriefRet;
import com.zjyz.pojo.param.ret.HelpArticleDetailRet;
import com.zjyz.service.HelpCenterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class HelpKnowledgeSkillHandler implements AgentSkillHandler {
    private static final int MAX_ARTICLES = 6;
    private static final int MAX_DETAIL_ARTICLES = 3;
    private static final int MAX_KEYWORDS = 8;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9]{2,16}");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("\\s+");
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(Arrays.asList(
            "怎么", "如何", "使用", "怎么用", "如何用", "操作", "说明", "步骤", "流程",
            "请问", "一下", "这个", "那个", "帮助", "文档", "管理", "功能", "模块", "系统"
    ));

    @Autowired
    private HelpCenterService helpCenterService;

    @Override
    public AgentIntentType supportedIntent() {
        return AgentIntentType.HELP_KNOWLEDGE;
    }

    @Override
    public AgentSkillExecution execute(String message, Map<String, Object> context, AgentSlotBag slots) {
        String safeMessage = StringUtils.hasText(message) ? message.trim() : "";
        String routeScope = normalizeScope(readContextString(context, "routeScope"), "all");
        String businessType = resolveBusinessType(safeMessage, slots);
        List<String> memoryTitles = readMemoryArticleTitles(context);

        if (isLocationFollowUp(safeMessage)) {
            return buildLocationExecution(safeMessage, businessType, routeScope, memoryTitles);
        }

        List<String> keywordCandidates = buildKeywordCandidates(safeMessage);
        List<HelpArticleBriefRet> briefs = searchArticles(keywordCandidates, routeScope, businessType);
        List<HelpArticleDetailRet> details = loadArticleDetails(briefs);

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("help_knowledge");

        List<String> warnings = new ArrayList<>();
        String answer;
        double confidence;

        if (CollectionUtils.isEmpty(details)) {
            answer = "帮助中心暂未检索到与当前问题直接匹配的文档。"
                    + "建议尝试更具体问法，例如：项目管理怎么使用、进销存管理怎么使用、如何查询并导出对账单。";
            confidence = 0.62;
            warnings.add("帮助中心检索结果为空或详情不可用，请补充更明确的功能关键词。");
        } else {
            List<String> tokens = extractMessageTokens(safeMessage);
            List<String> steps = buildActionSteps(details, tokens);
            String topic = resolveTopic(safeMessage);

            StringBuilder sb = new StringBuilder();
            sb.append("根据帮助中心文档，");
            if (StringUtils.hasText(topic)) {
                sb.append(topic).append("可按以下方式使用：");
            } else {
                sb.append("该功能可按以下方式使用：");
            }

            for (int i = 0; i < steps.size(); i++) {
                sb.append("\n").append(i + 1).append(". ").append(steps.get(i));
            }

            String refs = details.stream()
                    .map(HelpArticleDetailRet::getArticleTitle)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(Collectors.joining("、"));
            if (StringUtils.hasText(refs)) {
                sb.append("\n参考文档：").append(refs).append("。可在帮助中心按标题搜索查看完整图文步骤。");
            }

            answer = sb.toString();
            confidence = details.size() >= 2 ? 0.92 : 0.86;
        }

        execution.setAnswer(answer);
        execution.setConfidence(confidence);
        execution.setWarnings(warnings);
        execution.setCards(Collections.singletonList(buildCard(safeMessage, businessType, routeScope, keywordCandidates, details)));
        execution.setEvidence(buildEvidence(details.size()));
        return execution;
    }

    private AgentSkillExecution buildLocationExecution(String message,
                                                       String businessType,
                                                       String routeScope,
                                                       List<String> memoryTitles) {
        StringBuilder answer = new StringBuilder();
        answer.append("这些文档在系统左侧菜单【帮助中心】可以找到。")
                .append("路径：进入主页面 -> 点击左侧【帮助中心】 -> 在搜索框输入文档标题。")
                .append("如果你在与小云的对话里拿到文档名，可以直接复制标题到帮助中心搜索。");

        if (!CollectionUtils.isEmpty(memoryTitles)) {
            String refs = memoryTitles.stream().limit(5).collect(Collectors.joining("、"));
            answer.append(" 你上一条提到的文档包括：").append(refs).append("。");
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "help-knowledge-location");
        card.put("query", message);
        card.put("businessType", businessType);
        card.put("routeScope", routeScope);
        card.put("articles", memoryTitles == null ? Collections.emptyList() : memoryTitles);

        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent("help_knowledge");
        execution.setAnswer(answer.toString());
        execution.setConfidence(0.95);
        execution.setWarnings(Collections.emptyList());
        execution.setCards(Collections.singletonList(card));
        execution.setEvidence(buildEvidence(memoryTitles == null ? 0 : memoryTitles.size()));
        return execution;
    }

    private boolean isLocationFollowUp(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return containsAny(message, "这些文档", "这些文章", "上面的文档", "上面的文章", "文档在哪里", "文档在哪", "在哪里可以找到", "在哪可以找到", "到哪里找", "入口在哪", "哪里看", "去哪里看");
    }

    private List<HelpArticleBriefRet> searchArticles(List<String> keywords, String routeScope, String businessType) {
        Map<Long, HelpArticleBriefRet> merged = new LinkedHashMap<>();

        for (String keyword : keywords) {
            if (merged.size() >= MAX_ARTICLES) {
                break;
            }
            List<HelpArticleBriefRet> list = helpCenterService.queryArticleList(null, keyword, routeScope, businessType);
            mergeArticles(merged, list);
        }

        if (merged.isEmpty() && StringUtils.hasText(businessType) && !"all".equalsIgnoreCase(businessType)) {
            mergeArticles(merged, helpCenterService.queryRecommendList(routeScope, businessType, MAX_ARTICLES));
        }
        if (merged.isEmpty()) {
            mergeArticles(merged, helpCenterService.queryRecommendList(routeScope, "all", MAX_ARTICLES));
        }
        if (merged.isEmpty() && !"all".equalsIgnoreCase(routeScope)) {
            mergeArticles(merged, helpCenterService.queryRecommendList("all", "all", MAX_ARTICLES));
        }

        return new ArrayList<>(merged.values());
    }

    private void mergeArticles(Map<Long, HelpArticleBriefRet> merged, List<HelpArticleBriefRet> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        for (HelpArticleBriefRet item : list) {
            if (item == null || item.getArticleId() == null) {
                continue;
            }
            merged.putIfAbsent(item.getArticleId(), item);
            if (merged.size() >= MAX_ARTICLES) {
                return;
            }
        }
    }

    private List<HelpArticleDetailRet> loadArticleDetails(List<HelpArticleBriefRet> briefs) {
        if (CollectionUtils.isEmpty(briefs)) {
            return Collections.emptyList();
        }

        List<HelpArticleDetailRet> details = new ArrayList<>();
        for (HelpArticleBriefRet brief : briefs) {
            if (details.size() >= MAX_DETAIL_ARTICLES) {
                break;
            }
            if (brief == null || brief.getArticleId() == null) {
                continue;
            }
            try {
                HelpArticleDetailRet detail = helpCenterService.queryArticleDetail(brief.getArticleId());
                if (detail != null) {
                    details.add(detail);
                }
            } catch (Exception ignore) {
                // 单篇详情失败不影响整体问答
            }
        }
        return details;
    }

    private List<String> buildActionSteps(List<HelpArticleDetailRet> details, List<String> tokens) {
        List<String> steps = new ArrayList<>();
        for (HelpArticleDetailRet detail : details) {
            if (steps.size() >= 3) {
                break;
            }
            String guidance = buildGuidance(detail, tokens);
            if (!StringUtils.hasText(guidance)) {
                continue;
            }
            String title = StringUtils.hasText(detail.getArticleTitle()) ? detail.getArticleTitle() : "帮助文档";
            steps.add("【" + title + "】" + guidance);
        }

        if (steps.isEmpty()) {
            steps.add("进入帮助中心后先选择对应模块，再按文档中的步骤逐项执行。若涉及单据流程，优先确认项目与业务类型后再创建。");
        }
        return steps;
    }

    private String buildGuidance(HelpArticleDetailRet detail, List<String> tokens) {
        String summary = cleanText(detail == null ? null : detail.getArticleSummary());
        String content = cleanText(detail == null ? null : detail.getArticleContent());

        List<String> sentences = splitSentences(content);
        String best = findBestSentence(sentences, tokens);

        if (StringUtils.hasText(summary) && StringUtils.hasText(best) && !summary.contains(best)) {
            return clip(summary, 42) + "；" + clip(best, 54);
        }
        if (StringUtils.hasText(best)) {
            return clip(best, 88);
        }
        return clip(summary, 88);
    }

    private String findBestSentence(List<String> sentences, List<String> tokens) {
        if (CollectionUtils.isEmpty(sentences)) {
            return "";
        }
        if (!CollectionUtils.isEmpty(tokens)) {
            for (String sentence : sentences) {
                String lower = sentence.toLowerCase(Locale.ROOT);
                for (String token : tokens) {
                    if (lower.contains(token.toLowerCase(Locale.ROOT))) {
                        return sentence;
                    }
                }
            }
        }

        for (String sentence : sentences) {
            if (containsAny(sentence, "点击", "进入", "选择", "创建", "查询", "导出", "下载", "保存", "确认")) {
                return sentence;
            }
        }

        return sentences.get(0);
    }

    private List<String> splitSentences(String content) {
        if (!StringUtils.hasText(content)) {
            return Collections.emptyList();
        }
        String normalized = content.replace('!', '！').replace('?', '？').replace(';', '；');
        String[] arr = normalized.split("[。！？；\\n]");
        List<String> result = new ArrayList<>();
        for (String item : arr) {
            String sentence = item == null ? "" : item.trim();
            if (sentence.length() >= 6) {
                result.add(sentence);
            }
        }
        return result;
    }

    private List<String> buildKeywordCandidates(String message) {
        if (!StringUtils.hasText(message)) {
            return Arrays.asList("项目管理", "进销存", "库存", "对账");
        }

        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (containsAny(message, "项目管理", "项目")) {
            set.add("项目管理");
        }
        if (containsAny(message, "进销存", "库存", "盘点", "采购", "入库", "出库")) {
            set.add("进销存");
            set.add("库存");
        }
        if (containsAny(message, "租出", "归还")) {
            set.add("租出");
        }
        if (containsAny(message, "租入", "退租")) {
            set.add("租入");
        }
        if (containsAny(message, "对账", "结算", "财务")) {
            set.add("对账");
        }
        if (containsAny(message, "单据", "下载", "导出")) {
            set.add("单据");
        }

        List<String> tokens = extractMessageTokens(message);
        for (String token : tokens) {
            set.add(token);
            if (set.size() >= MAX_KEYWORDS) {
                break;
            }
        }

        if (set.isEmpty()) {
            set.add("项目管理");
            set.add("进销存");
        }
        return new ArrayList<>(set);
    }

    private List<String> extractMessageTokens(String message) {
        if (!StringUtils.hasText(message)) {
            return Collections.emptyList();
        }
        Matcher matcher = TOKEN_PATTERN.matcher(message);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (STOP_WORDS.contains(token)) {
                continue;
            }
            if (token.length() > 12) {
                continue;
            }
            tokens.add(token);
            if (tokens.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return new ArrayList<>(tokens);
    }

    private String resolveTopic(String message) {
        if (!StringUtils.hasText(message)) {
            return "";
        }
        if (containsAny(message, "项目管理", "项目")) {
            return "项目管理";
        }
        if (containsAny(message, "进销存", "库存", "采购", "入库", "出库", "盘点")) {
            return "进销存管理";
        }
        if (containsAny(message, "租出", "归还")) {
            return "租出管理";
        }
        if (containsAny(message, "租入", "退租")) {
            return "租入管理";
        }
        if (containsAny(message, "对账", "结算", "财务")) {
            return "对账管理";
        }
        return "";
    }

    private String resolveBusinessType(String message, AgentSlotBag slots) {
        String slotBusinessType = slots == null ? null : slots.getProjectBusinessType();
        if ("rent_in".equalsIgnoreCase(slotBusinessType)) {
            return "rent_in";
        }
        if ("rent_out".equalsIgnoreCase(slotBusinessType)) {
            return "rent_out";
        }
        if (containsAny(message, "租入", "退租")) {
            return "rent_in";
        }
        if (containsAny(message, "租出", "归还")) {
            return "rent_out";
        }
        return "all";
    }

    private List<String> readMemoryArticleTitles(Map<String, Object> context) {
        if (context == null) {
            return Collections.emptyList();
        }
        Object titlesObj = context.get("memoryHelpArticleTitles");
        if (!(titlesObj instanceof List)) {
            return Collections.emptyList();
        }
        List<?> rawList = (List<?>) titlesObj;
        List<String> titles = new ArrayList<>();
        for (Object item : rawList) {
            String title = item == null ? "" : String.valueOf(item).trim();
            if (StringUtils.hasText(title)) {
                titles.add(title);
            }
        }
        return titles;
    }

    private Map<String, Object> buildCard(String query,
                                          String businessType,
                                          String routeScope,
                                          List<String> keywords,
                                          List<HelpArticleDetailRet> details) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "help-knowledge");
        card.put("query", query);
        card.put("businessType", businessType);
        card.put("routeScope", routeScope);
        card.put("keywords", keywords);
        card.put("articleCount", details == null ? 0 : details.size());

        List<Map<String, Object>> articleRefs = new ArrayList<>();
        if (!CollectionUtils.isEmpty(details)) {
            for (HelpArticleDetailRet detail : details) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("articleId", detail.getArticleId());
                item.put("title", detail.getArticleTitle());
                item.put("categoryName", detail.getCategoryName());
                item.put("summary", clip(cleanText(detail.getArticleSummary()), 80));
                item.put("updateTime", detail.getUpdateTime() == null ? null : detail.getUpdateTime().toString());
                articleRefs.add(item);
            }
        }
        card.put("articles", articleRefs);
        return card;
    }

    private AgentEvidence buildEvidence(int recordCount) {
        AgentEvidence evidence = new AgentEvidence();
        evidence.setTimeRange("asOf=" + LocalDate.now());
        evidence.setSkills(Collections.singletonList("HelpCenterKnowledgeSkill"));
        evidence.setApiList(Arrays.asList("/help/article/list", "/help/article/detail", "/help/article/recommend"));
        evidence.setRecordCount(recordCount);
        return evidence;
    }

    private String clip(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    private String cleanText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String text = raw;
        text = HTML_TAG_PATTERN.matcher(text).replaceAll(" ");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
                .replace("&quot;", "\"");
        return MULTI_SPACE_PATTERN.matcher(text).replaceAll(" ").trim();
    }

    private String normalizeScope(String value, String defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        return value.trim();
    }

    private String readContextString(Map<String, Object> context, String key) {
        if (context == null || !context.containsKey(key) || context.get(key) == null) {
            return null;
        }
        String value = String.valueOf(context.get(key)).trim();
        return StringUtils.hasText(value) ? value : null;
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
}
