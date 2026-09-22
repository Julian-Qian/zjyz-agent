package com.zjyz.agent.orch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentSessionMemoryStore {
    private final ConcurrentHashMap<String, MemoryEntry> sessionMemory = new ConcurrentHashMap<>();

    @Value("${agent.session.memory.ttlMinutes:45}")
    private long ttlMinutes;

    @Value("${agent.session.memory.maxSessions:2000}")
    private int maxSessions;

    public MemorySnapshot get(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        MemoryEntry entry = sessionMemory.get(sessionId);
        if (entry == null) {
            return null;
        }
        if (isExpired(entry)) {
            sessionMemory.remove(sessionId);
            return null;
        }
        return toSnapshot(entry);
    }

    public void save(String sessionId, String userMessage, AgentSkillExecution execution, String finalAnswer) {
        if (!StringUtils.hasText(sessionId) || execution == null) {
            return;
        }

        cleanup();

        MemoryEntry entry = new MemoryEntry();
        entry.updatedAt = LocalDateTime.now();
        entry.lastIntent = trim(userSafe(execution.getIntent()), 80);
        entry.lastUserMessage = trim(userSafe(userMessage), 240);
        entry.lastAnswer = trim(userSafe(finalAnswer), 360);
        entry.needClarification = Boolean.TRUE.equals(execution.getNeedClarification());
        entry.missingSlots = copyStrings(execution.getMissingSlots());
        entry.lastCards = copyCards(execution.getCards());
        entry.helpArticleTitles = extractHelpArticleTitles(entry.lastCards);
        sessionMemory.put(sessionId, entry);
    }

    private MemorySnapshot toSnapshot(MemoryEntry entry) {
        MemorySnapshot snapshot = new MemorySnapshot();
        snapshot.setLastIntent(entry.lastIntent);
        snapshot.setLastUserMessage(entry.lastUserMessage);
        snapshot.setLastAnswer(entry.lastAnswer);
        snapshot.setNeedClarification(entry.needClarification);
        snapshot.setMissingSlots(entry.missingSlots == null
                ? Collections.emptyList()
                : new ArrayList<>(entry.missingSlots));
        snapshot.setCards(copyCards(entry.lastCards));
        snapshot.setHelpArticleTitles(entry.helpArticleTitles == null
                ? Collections.emptyList()
                : new ArrayList<>(entry.helpArticleTitles));
        snapshot.setUpdatedAt(entry.updatedAt);
        return snapshot;
    }

    private List<Map<String, Object>> copyCards(List<Map<String, Object>> cards) {
        if (CollectionUtils.isEmpty(cards)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> copied = new ArrayList<>();
        for (Map<String, Object> card : cards) {
            if (card == null) {
                continue;
            }
            copied.add(new LinkedHashMap<>(card));
        }
        return copied;
    }

    private List<String> copyStrings(List<String> values) {
        if (CollectionUtils.isEmpty(values)) {
            return Collections.emptyList();
        }
        List<String> copied = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                copied.add(value.trim());
            }
        }
        return copied;
    }

    private List<String> extractHelpArticleTitles(List<Map<String, Object>> cards) {
        if (CollectionUtils.isEmpty(cards)) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        for (Map<String, Object> card : cards) {
            if (card == null) {
                continue;
            }
            if (!"help-knowledge".equals(String.valueOf(card.get("type")))) {
                continue;
            }
            Object articlesObj = card.get("articles");
            if (!(articlesObj instanceof List)) {
                continue;
            }
            List<?> articles = (List<?>) articlesObj;
            for (Object articleObj : articles) {
                if (!(articleObj instanceof Map)) {
                    continue;
                }
                Object titleObj = ((Map<?, ?>) articleObj).get("title");
                String title = titleObj == null ? "" : String.valueOf(titleObj).trim();
                if (StringUtils.hasText(title)) {
                    titles.add(title);
                }
            }
        }
        return new ArrayList<>(titles);
    }

    private boolean isExpired(MemoryEntry entry) {
        if (entry == null || entry.updatedAt == null) {
            return true;
        }
        return entry.updatedAt.plusMinutes(Math.max(ttlMinutes, 1)).isBefore(LocalDateTime.now());
    }

    private void cleanup() {
        if (sessionMemory.isEmpty()) {
            return;
        }

        List<Map.Entry<String, MemoryEntry>> entries = new ArrayList<>(sessionMemory.entrySet());
        for (Map.Entry<String, MemoryEntry> item : entries) {
            if (isExpired(item.getValue())) {
                sessionMemory.remove(item.getKey());
            }
        }

        int overflow = sessionMemory.size() - Math.max(maxSessions, 100);
        if (overflow <= 0) {
            return;
        }

        List<Map.Entry<String, MemoryEntry>> remaining = new ArrayList<>(sessionMemory.entrySet());
        remaining.sort(Comparator.comparing(o -> o.getValue().updatedAt));
        for (int i = 0; i < overflow && i < remaining.size(); i++) {
            sessionMemory.remove(remaining.get(i).getKey());
        }
    }

    private String userSafe(String value) {
        return value == null ? "" : value;
    }

    private String trim(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxLen - 1)) + "…";
    }

    private static class MemoryEntry {
        private String lastIntent;
        private String lastUserMessage;
        private String lastAnswer;
        private boolean needClarification;
        private List<String> missingSlots = Collections.emptyList();
        private List<Map<String, Object>> lastCards = Collections.emptyList();
        private List<String> helpArticleTitles = Collections.emptyList();
        private LocalDateTime updatedAt;
    }

    public static class MemorySnapshot {
        private String lastIntent;
        private String lastUserMessage;
        private String lastAnswer;
        private boolean needClarification;
        private List<String> missingSlots = Collections.emptyList();
        private List<Map<String, Object>> cards = Collections.emptyList();
        private List<String> helpArticleTitles = Collections.emptyList();
        private LocalDateTime updatedAt;

        public String getLastIntent() {
            return lastIntent;
        }

        public void setLastIntent(String lastIntent) {
            this.lastIntent = lastIntent;
        }

        public String getLastUserMessage() {
            return lastUserMessage;
        }

        public void setLastUserMessage(String lastUserMessage) {
            this.lastUserMessage = lastUserMessage;
        }

        public String getLastAnswer() {
            return lastAnswer;
        }

        public void setLastAnswer(String lastAnswer) {
            this.lastAnswer = lastAnswer;
        }

        public List<Map<String, Object>> getCards() {
            return cards;
        }

        public void setCards(List<Map<String, Object>> cards) {
            this.cards = cards;
        }

        public boolean isNeedClarification() {
            return needClarification;
        }

        public void setNeedClarification(boolean needClarification) {
            this.needClarification = needClarification;
        }

        public List<String> getMissingSlots() {
            return missingSlots;
        }

        public void setMissingSlots(List<String> missingSlots) {
            this.missingSlots = missingSlots;
        }

        public List<String> getHelpArticleTitles() {
            return helpArticleTitles;
        }

        public void setHelpArticleTitles(List<String> helpArticleTitles) {
            this.helpArticleTitles = helpArticleTitles;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
}
