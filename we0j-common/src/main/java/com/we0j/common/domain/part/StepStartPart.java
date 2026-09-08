package com.we0j.common.domain.part;

/** 步骤开始锚点：snapshot 为 shadow git 的 tree hash（git add -A + write-tree，不 commit，FR-101）。 */
public record StepStartPart(String id, String messageId, String sessionId, String snapshot) implements Part {}
