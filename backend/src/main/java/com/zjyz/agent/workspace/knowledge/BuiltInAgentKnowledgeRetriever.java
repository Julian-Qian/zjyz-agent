package com.zjyz.agent.workspace.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.context.AgentTaskFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
public class BuiltInAgentKnowledgeRetriever implements AgentKnowledgeRetriever {
    private static final String RESOURCE = "agent/knowledge/builtin-pack-v1.json";
    private final List<AgentKnowledgeEntry> entries;

    public BuiltInAgentKnowledgeRetriever(ObjectMapper objectMapper) {
        this.entries = load(objectMapper);
    }

    @Override
    public AgentKnowledgeContext retrieve(AgentTaskFrame taskFrame, int limit) {
        AgentKnowledgeContext context = new AgentKnowledgeContext();
        if (taskFrame == null || entries.isEmpty()) {
            return context;
        }
        String query = normalize(taskFrame.getUserGoal());
        List<ScoredEntry> scored = new ArrayList<>();
        for (AgentKnowledgeEntry entry : entries) {
            if (!isTrusted(entry)) {
                continue;
            }
            int score = score(entry, taskFrame, query);
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredEntry::getScore).reversed()
                .thenComparing(item -> item.getEntry().getId()));

        int max = Math.max(1, Math.min(limit, 6));
        List<AgentKnowledgeEntry> selected = scored.stream().limit(max)
                .map(ScoredEntry::getEntry).collect(Collectors.toList());
        context.setEntries(selected);
        context.setReferences(selected.stream().map(AgentKnowledgeReference::from).collect(Collectors.toList()));
        Set<String> hints = new LinkedHashSet<>();
        for (AgentKnowledgeEntry entry : selected) {
            if (!CollectionUtils.isEmpty(entry.getToolHints())) {
                hints.addAll(entry.getToolHints());
            }
        }
        context.setToolHints(hints);
        return context;
    }

    List<AgentKnowledgeEntry> trustedEntries() {
        return entries.stream().filter(this::isTrusted).collect(Collectors.toList());
    }

    private int score(AgentKnowledgeEntry entry, AgentTaskFrame frame, String query) {
        int score = 0;
        if (StringUtils.hasText(entry.getDomain()) && entry.getDomain().equalsIgnoreCase(frame.getDomain())) {
            score += 30;
        }
        if (!CollectionUtils.isEmpty(entry.getKeywords())) {
            for (String keyword : entry.getKeywords()) {
                if (StringUtils.hasText(keyword) && query.contains(normalize(keyword))) {
                    score += 8;
                }
            }
        }
        if (query.contains(normalize(entry.getTitle()))) {
            score += 12;
        }
        if (frame.isRequiresBusinessData() && "SAFETY_POLICY".equalsIgnoreCase(entry.getType())) {
            score += 25;
        }
        return score;
    }

    private boolean isTrusted(AgentKnowledgeEntry entry) {
        return entry != null
                && "APPROVED".equalsIgnoreCase(entry.getStatus())
                && ("RUNTIME_CODE".equalsIgnoreCase(entry.getAuthority())
                || "APPROVED_SPEC".equalsIgnoreCase(entry.getAuthority())
                || "PUBLISHED_HELP".equalsIgnoreCase(entry.getAuthority()));
    }

    private List<AgentKnowledgeEntry> load(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            List<AgentKnowledgeEntry> loaded = objectMapper.readValue(input,
                    new TypeReference<List<AgentKnowledgeEntry>>() { });
            return loaded == null ? new ArrayList<>() : loaded;
        } catch (Exception e) {
            log.error("failed to load built-in Agent knowledge pack: {}", RESOURCE, e);
            return new ArrayList<>();
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static class ScoredEntry {
        private final AgentKnowledgeEntry entry;
        private final int score;

        private ScoredEntry(AgentKnowledgeEntry entry, int score) {
            this.entry = entry;
            this.score = score;
        }

        private AgentKnowledgeEntry getEntry() {
            return entry;
        }

        private int getScore() {
            return score;
        }
    }
}
