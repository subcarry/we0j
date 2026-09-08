package com.we0j.common.exception;

/** 用户放弃问卷（FR-078）。 */
public class QuestionRejectedException extends We0jException {
    private final String requestId;

    public QuestionRejectedException(String requestId) {
        super("User dismissed the questionnaire without answering.");
        this.requestId = requestId;
    }

    public String requestId() { return requestId; }

    @Override public String userFacingMessage() { return getMessage(); }
}
