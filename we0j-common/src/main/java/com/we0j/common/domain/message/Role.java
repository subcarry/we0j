package com.we0j.common.domain.message;

/** 消息角色。仅作为 wire 判别值使用（见 {@link Message} 的 @JsonTypeInfo），不作为 record 组件。 */
public enum Role { USER, ASSISTANT }
