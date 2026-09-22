package com.zjyz.agent.workspace.tool;

import com.zjyz.agent.orch.AgentIntentType;
import lombok.Getter;

import java.util.Map;

@Getter
public class AgentToolDescriptor {
    /** 工具与项目多选的关系是显式契约的一部分，禁止隐式扩大或缩小范围。 */
    public static final String SCOPE_RESPECTS_SELECTION = "RESPECTS_SELECTION";
    public static final String SCOPE_IGNORES_SELECTION = "IGNORES_SELECTION";
    public static final String SCOPE_NOT_APPLICABLE = "NOT_APPLICABLE";

    private final String modelName;
    private final String toolCode;
    private final String description;
    private final String riskLevel;
    private final AgentIntentType intent;
    private final String selectionSupport;
    private final String scopeBehavior;
    private final Map<String, Object> parameters;

    public AgentToolDescriptor(String modelName,
                               String toolCode,
                               String description,
                               String riskLevel,
                               AgentIntentType intent,
                               String selectionSupport,
                               Map<String, Object> parameters) {
        this(modelName, toolCode, description, riskLevel, intent, selectionSupport,
                SCOPE_RESPECTS_SELECTION, parameters);
    }

    public AgentToolDescriptor(String modelName,
                               String toolCode,
                               String description,
                               String riskLevel,
                               AgentIntentType intent,
                               String selectionSupport,
                               String scopeBehavior,
                               Map<String, Object> parameters) {
        this.modelName = modelName;
        this.toolCode = toolCode;
        this.description = description;
        this.riskLevel = riskLevel;
        this.intent = intent;
        this.selectionSupport = selectionSupport;
        this.scopeBehavior = scopeBehavior;
        this.parameters = parameters;
    }
}
