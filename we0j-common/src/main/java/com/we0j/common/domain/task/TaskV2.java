package com.we0j.common.domain.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 结构化任务（FR-077）。blocks/blockedBy 双向维护；依赖是建议性的，运行时不强制阻塞，
 * 门控靠提示词（"claim 前检查 blockedBy 为空"）—— 避免死锁并保留 Agent 自主判断。
 */
public record TaskV2(
        String id,
        String subject,
        String description,
        String activeForm,
        String owner,
        TaskStatus status,
        List<String> blocks,
        List<String> blockedBy,
        Map<String, Object> metadata,
        Instant timeCreated,
        Instant timeUpdated,
        List<TaskComment> comments) {

    public TaskV2 {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        comments = comments == null ? List.of() : List.copyOf(comments);
    }

    /** blockedBy 为空且 PENDING 才可认领（prompt 级门控的机器可读版本）。派生值，不入 JSON。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isClaimable() {
        return status == TaskStatus.PENDING && blockedBy.isEmpty();
    }

    public TaskV2 withStatus(TaskStatus s) { return new TaskV2(id, subject, description, activeForm, owner, s, blocks, blockedBy, metadata, timeCreated, Instant.now(), comments); }
    public TaskV2 withSubject(String v) { return new TaskV2(id, v, description, activeForm, owner, status, blocks, blockedBy, metadata, timeCreated, Instant.now(), comments); }
    public TaskV2 withDescription(String v) { return new TaskV2(id, subject, v, activeForm, owner, status, blocks, blockedBy, metadata, timeCreated, Instant.now(), comments); }
    public TaskV2 withActiveForm(String v) { return new TaskV2(id, subject, description, v, owner, status, blocks, blockedBy, metadata, timeCreated, Instant.now(), comments); }
    public TaskV2 withOwner(String v) { return new TaskV2(id, subject, description, activeForm, v, status, blocks, blockedBy, metadata, timeCreated, Instant.now(), comments); }
    public TaskV2 withBlocks(List<String> v) { return new TaskV2(id, subject, description, activeForm, owner, status, v, blockedBy, metadata, timeCreated, Instant.now(), comments); }
    public TaskV2 withBlockedBy(List<String> v) { return new TaskV2(id, subject, description, activeForm, owner, status, blocks, v, metadata, timeCreated, Instant.now(), comments); }
    public TaskV2 withMetadata(Map<String, Object> v) { return new TaskV2(id, subject, description, activeForm, owner, status, blocks, blockedBy, v, timeCreated, Instant.now(), comments); }
    public TaskV2 withComments(List<TaskComment> v) { return new TaskV2(id, subject, description, activeForm, owner, status, blocks, blockedBy, metadata, timeCreated, Instant.now(), v); }
}
