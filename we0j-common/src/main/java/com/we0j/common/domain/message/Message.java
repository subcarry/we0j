package com.we0j.common.domain.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 消息多态根（DDD §3.1 实现注意）：role 判别字段由 @JsonTypeInfo 写出，
 * 不作为 record 组件，也**不声明 role() 访问器** —— 避免与 type-id 属性冲突。
 * 调用方一律用模式匹配 switch（sealed 保证穷尽）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "role")
@JsonSubTypes({
        @JsonSubTypes.Type(value = UserMessage.class, name = "user"),
        @JsonSubTypes.Type(value = AssistantMessage.class, name = "assistant")
})
public sealed interface Message permits UserMessage, AssistantMessage {

    String id();

    String sessionId();

    /** 创建时间；两实现分别从 TimeCreated / TimeCreatedCompleted 提取。 */
    java.time.Instant timeCreated();
}
