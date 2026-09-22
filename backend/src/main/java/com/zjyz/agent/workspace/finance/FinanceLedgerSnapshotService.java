package com.zjyz.agent.workspace.finance;

import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Query;
import com.zjyz.agent.workspace.finance.FinanceLedgerSnapshotModels.Result;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;

public interface FinanceLedgerSnapshotService {
    enum LedgerDirection {
        RENT_OUT,
        RENT_IN,
        BOTH
    }

    Result generate(AgentRuntimeRecords.Workspace workspace, Query query);

    Result generate(AgentRuntimeRecords.Workspace workspace, Query query, LedgerDirection direction);
}
