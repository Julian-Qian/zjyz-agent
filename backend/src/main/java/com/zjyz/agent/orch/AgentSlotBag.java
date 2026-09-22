package com.zjyz.agent.orch;

import lombok.Data;

@Data
public class AgentSlotBag {
    private String projectId;
    private String projectKeyword;
    private Integer projectOrdinal;
    private String projectBusinessType;
    private String queryType;
    private boolean overdueRequested;
    private boolean trendRequested;

    private String estimateProjectName;
    private Double buildingArea;
    private Integer floorAbove;
    private Integer floorBelow;
    private String buildingType;
    private String structureType;
    private Double wallPerimeter;
    private Double buildingHeight;
    private Integer rentalDays;
    private String region;
    private String specialRequirement;
}
