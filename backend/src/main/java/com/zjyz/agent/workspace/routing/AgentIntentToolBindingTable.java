package com.zjyz.agent.workspace.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.tool.AgentToolCodes;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 版本化"指标 → 最小必要工具"下限绑定表（P0-2）。
 *
 * 定位是召回保底与证据下限校验，不是排他路由：
 * - AgentTaskFrameBuilder 在其守卫分支全部通过后，把命中的下限工具并入 minimumRequiredTools；
 * - AgentToolCatalog 保证 minimumRequiredTools 无条件暴露给模型；
 * - V2 执行器用下限工具校验证据链完整性。
 * 规则、语义红线与版本号来自 classpath 配置 agent/routing/intent-tool-binding-v1.json，
 * 配置变更必须升版本号，bindingVersion 随任务帧与 tool.exposed 事件落盘以便审计回溯。
 */
public final class AgentIntentToolBindingTable {
    private static final String RESOURCE = "agent/routing/intent-tool-binding-v1.json";
    private static final AgentIntentToolBindingTable INSTANCE = new AgentIntentToolBindingTable(RESOURCE);

    private final String bindingVersion;
    private final List<Rule> rules;
    private final List<String> semanticExclusionStatements;

    public static AgentIntentToolBindingTable instance() {
        return INSTANCE;
    }

    AgentIntentToolBindingTable(String resource) {
        try (InputStream input = AgentIntentToolBindingTable.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing intent tool binding resource: " + resource);
            }
            JsonNode root = new ObjectMapper().readTree(input);
            String version = root.path("bindingVersion").asText("");
            if (!StringUtils.hasText(version)) {
                throw new IllegalStateException("intent tool binding requires bindingVersion");
            }
            this.bindingVersion = version;
            List<Rule> loadedRules = new ArrayList<>();
            for (JsonNode node : root.path("rules")) {
                loadedRules.add(Rule.from(node));
            }
            if (loadedRules.isEmpty()) {
                throw new IllegalStateException("intent tool binding requires at least one rule");
            }
            this.rules = Collections.unmodifiableList(loadedRules);
            List<String> statements = new ArrayList<>();
            for (JsonNode node : root.path("semanticExclusions")) {
                String statement = node.path("statement").asText("");
                if (StringUtils.hasText(statement)) {
                    statements.add(statement);
                }
            }
            this.semanticExclusionStatements = Collections.unmodifiableList(statements);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load intent tool binding table: " + e.getMessage(), e);
        }
    }

    public String bindingVersion() {
        return bindingVersion;
    }

    public List<String> semanticExclusionStatements() {
        return semanticExclusionStatements;
    }

    /** 系统提示词用的语义红线段落。 */
    public String semanticExclusionPrompt() {
        return "业务语义红线：" + String.join("", semanticExclusionStatements);
    }

    /** 返回消息在当前范围下命中的下限工具（canonical code，去重保序）。 */
    public List<String> floorToolCodes(String message, String selectionKind) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(message)) {
            return result;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        String kind = selectionKind == null ? "" : selectionKind.trim().toUpperCase(Locale.ROOT);
        for (Rule rule : rules) {
            if (rule.matches(normalized, kind)) {
                for (String code : rule.requiredToolCodes) {
                    if (!result.contains(code)) {
                        result.add(code);
                    }
                }
            }
        }
        return result;
    }

    /** 把下限工具并入既有必需工具列表（去重保序）；既有列表在前，下限补在其后。 */
    public List<String> applyFloor(String message, String selectionKind, List<String> required) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (required != null) {
            for (String code : required) {
                String canonical = AgentToolCodes.canonicalize(code);
                if (StringUtils.hasText(canonical)) {
                    merged.add(canonical);
                }
            }
        }
        merged.addAll(floorToolCodes(message, selectionKind));
        return new ArrayList<>(merged);
    }

    List<Rule> rules() {
        return rules;
    }

    static final class Rule {
        final String id;
        final List<String> anyOf;
        final List<List<String>> allOfGroups;
        final List<String> excludeKeywords;
        final List<String> requiredToolCodes;
        final List<String> selectionModes;

        private Rule(String id,
                     List<String> anyOf,
                     List<List<String>> allOfGroups,
                     List<String> excludeKeywords,
                     List<String> requiredToolCodes,
                     List<String> selectionModes) {
            this.id = id;
            this.anyOf = anyOf;
            this.allOfGroups = allOfGroups;
            this.excludeKeywords = excludeKeywords;
            this.requiredToolCodes = requiredToolCodes;
            this.selectionModes = selectionModes;
        }

        static Rule from(JsonNode node) {
            String id = node.path("id").asText("");
            if (!StringUtils.hasText(id)) {
                throw new IllegalStateException("binding rule requires id");
            }
            List<String> anyOf = textList(node.path("anyOf"));
            List<List<String>> allOfGroups = new ArrayList<>();
            for (JsonNode group : node.path("allOfGroups")) {
                List<String> keywords = textList(group);
                if (!keywords.isEmpty()) {
                    allOfGroups.add(keywords);
                }
            }
            if (anyOf.isEmpty() && allOfGroups.isEmpty()) {
                throw new IllegalStateException("binding rule " + id + " requires anyOf or allOfGroups");
            }
            List<String> tools = new ArrayList<>();
            for (String code : textList(node.path("requiredToolCodes"))) {
                String canonical = AgentToolCodes.canonicalize(code);
                if (!canonical.equals(code)) {
                    throw new IllegalStateException("binding rule " + id + " must use canonical tool code: " + code);
                }
                tools.add(canonical);
            }
            if (tools.isEmpty()) {
                throw new IllegalStateException("binding rule " + id + " requires requiredToolCodes");
            }
            return new Rule(id, anyOf, allOfGroups, textList(node.path("excludeKeywords")),
                    Collections.unmodifiableList(tools), textList(node.path("selectionModes")));
        }

        boolean matches(String normalizedMessage, String selectionKind) {
            if (!selectionModes.isEmpty() && !selectionModes.contains(selectionKind)) {
                return false;
            }
            for (String keyword : excludeKeywords) {
                if (normalizedMessage.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            if (!anyOf.isEmpty() && containsAny(normalizedMessage, anyOf)) {
                return true;
            }
            if (!allOfGroups.isEmpty()) {
                for (List<String> group : allOfGroups) {
                    if (!containsAny(normalizedMessage, group)) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }

        private boolean containsAny(String normalizedMessage, List<String> keywords) {
            for (String keyword : keywords) {
                if (normalizedMessage.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        private static List<String> textList(JsonNode node) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("");
                if (StringUtils.hasText(value)) {
                    result.add(value.trim());
                }
            }
            return result;
        }
    }
}
