package com.we0j.server.dto;

import java.util.List;

/** 请求体聚纳（DDD §8.2；全部 record，无校验注解——校验在 controller 手工做并归一 VALIDATION_FAILED）。 */
public final class Requests {

    private Requests() {
    }

    /** POST /sessions。 */
    public record CreateSession(String workdir, String parentId, String agentName, String modelRef,
                                String permissionMode) {
    }

    /** POST /sessions/{id}/prompt。 */
    public record Prompt(String text, List<Object> attachments, String agentName, String modelRef) {
    }

    /** POST /sessions/{id}/compact。 */
    public record Compact(String instruction) {
    }

    /** POST /sessions/{id}/mode。 */
    public record Mode(String agentName) {
    }

    /** POST /sessions/{id}/permission-mode。 */
    public record PermissionModeRef(String mode) {
    }

    /** POST /sessions/{id}/rename。 */
    public record Rename(String title) {
    }

    /** POST /sessions/{id}/rewind；mode = CONVERSATION | BOTH。 */
    public record Rewind(String targetMessageId, String mode) {
    }

    /** POST /permissions/{requestId}/reply；reply = ONCE | ALWAYS | REJECT。 */
    public record PermissionReply(String sessionId, String reply, String userMessage) {
    }

    /** POST /questions/{requestId}/reply；answers 每题一个选项数组。 */
    public record QuestionReply(String sessionId, List<List<String>> answers) {
    }

    /** POST /questions/{requestId}/reject。 */
    public record QuestionReject(String sessionId) {
    }

    /** POST /providers/{id}；null 字段 = 不修改。 */
    public record ProviderUpdate(Boolean enabled, String apiKey, String apiBase) {
    }

    /** POST /providers/default：切默认厂家档位（model 缺省取该厂家首个已配置模型）。 */
    public record DefaultRef(String provider, String model) {
    }
}
