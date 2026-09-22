package com.zjyz.agent.workspace.finance;

import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Query;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Result;

public interface ReceivableCollectionService {
    Result generate(String cid, Query query);
}
