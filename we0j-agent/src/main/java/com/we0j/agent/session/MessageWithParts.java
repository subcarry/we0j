package com.we0j.agent.session;

import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.part.Part;
import java.util.List;

/**
 * 消息 + 其 Part 列表的组装视图（DDD §5.1 SessionStateCache.history 的返回单元）。
 * parts 按插入序（timeCreated 升序 + 同刻保持插入顺序的稳定合并）。
 */
public record MessageWithParts(Message message, List<Part> parts) {

    public MessageWithParts {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
