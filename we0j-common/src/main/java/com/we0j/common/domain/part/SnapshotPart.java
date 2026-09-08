package com.we0j.common.domain.part;

/** 快照标记片段。 */
public record SnapshotPart(String id, String messageId, String sessionId) implements Part {}
