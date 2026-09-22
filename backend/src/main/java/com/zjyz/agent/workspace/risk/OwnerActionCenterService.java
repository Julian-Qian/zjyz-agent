package com.zjyz.agent.workspace.risk;

import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.Query;
import com.zjyz.agent.workspace.risk.OwnerActionCenterModels.Result;

public interface OwnerActionCenterService {
    Result generate(AgentRuntimeRecords.Workspace workspace, Query query);
}
