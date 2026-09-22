package com.zjyz.agent.orch;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zjyz.agent.model.AgentEvidence;
import lombok.Data;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
public class AgentSkillExecution {
    private String intent;
    private Double confidence;
    private String answer;
    private List<Map<String, Object>> cards = Collections.emptyList();
    private List<String> warnings = Collections.emptyList();
    private AgentEvidence evidence;

    @JsonIgnore
    private String artifactTitle;
    @JsonIgnore
    private String artifactType;
    @JsonIgnore
    private String artifactMimeType;
    @JsonIgnore
    private String artifactContentJson;

    private Boolean needClarification;
    private String clarificationQuestion;
    private List<String> missingSlots = Collections.emptyList();

    public static AgentSkillExecution clarification(String intent,
                                                    String question,
                                                    List<String> missingSlots,
                                                    AgentEvidence evidence) {
        AgentSkillExecution execution = new AgentSkillExecution();
        execution.setIntent(intent);
        execution.setConfidence(0.86);
        execution.setAnswer(question);
        execution.setNeedClarification(true);
        execution.setClarificationQuestion(question);
        execution.setMissingSlots(missingSlots == null ? Collections.emptyList() : missingSlots);
        execution.setEvidence(evidence);
        return execution;
    }
}
