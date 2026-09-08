package com.we0j.server.dto;

import com.we0j.common.domain.message.Message;
import com.we0j.common.domain.part.Part;
import java.util.List;

/**
 * 消息 + parts（DDD §8.4）。★ 偏差说明：message/parts 直接用领域对象序列化——
 * Message 带 @JsonTypeInfo(role)、Part 带 @JsonTypeInfo(type)，11 种 Part / 2 种 Message
 * 的多态判别与 ISO-8601 Instant（Boot JavaTimeModule + write-dates-as-timestamps:false）
 * 已由领域侧注解完备覆盖，再抄一层 PartDto 是零信息量的映射税。
 */
public record MessageWithPartsDto(Message message, List<Part> parts) {

    public MessageWithPartsDto {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
