package com.zjyz.agent.workspace.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Executes legacy compute capabilities without committing their incidental database writes.
 */
@Component
public class AgentComputeSandbox {
    private final PlatformTransactionManager transactionManager;

    public AgentComputeSandbox(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public <T> T executeWithoutCommit(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> {
            T result = action.get();
            status.setRollbackOnly();
            return result;
        });
    }
}
