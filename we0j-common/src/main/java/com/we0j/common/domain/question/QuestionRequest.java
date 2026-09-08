package com.we0j.common.domain.question;

import java.util.List;
import java.util.Map;

/** AskUserQuestion 的运行时请求（1-4 问，FR-078）。 */
public record QuestionRequest(
        String id,
        String sessionId,
        List<QuestionInfo> questions,
        Map<String, Object> metadata,
        QuestionToolRef tool) {

    public QuestionRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public QuestionRequest withSessionId(String newSessionId) {
        return new QuestionRequest(id, newSessionId, questions, metadata, tool);
    }
}
