package com.we0j.common.domain.part;

import com.we0j.common.domain.message.TimeCreated;
import com.we0j.common.domain.part.MessageError;

/** 重试片段：模型请求层重试的用户可见记录（FR-036）。 */
public record RetryPart(String id, String messageId, String sessionId,
                        MessageError error, TimeCreated time) implements Part {}
