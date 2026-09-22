package com.zjyz.agent.workspace.knowledge;

public class KnowledgeProcessingException extends RuntimeException {
    private final String errorCode;

    public KnowledgeProcessingException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public KnowledgeProcessingException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
