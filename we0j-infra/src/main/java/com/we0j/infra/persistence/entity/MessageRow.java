package com.we0j.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * {@code message} 表行 —— 薄壳表 + JSON blob（DDD §4.5.1 / §7.1，FR-09）。
 *
 * <p>{@code data} 承载 {@code UserMessage | AssistantMessage} 的多态 JSON blob
 * （判别字段 {@code role}，见 {@code com.we0j.common.domain.message.Message}）。
 * 列侧额外冗余 {@code role} 索引列：「查所有 assistant 消息」无需反序列化全部 blob。
 */
@Entity
@Table(name = "message", indexes = {
        @Index(name = "idx_message_session", columnList = "session_id"),
        @Index(name = "idx_message_session_created", columnList = "session_id,time_created"),
        @Index(name = "idx_message_session_role", columnList = "session_id,role")
})
public class MessageRow {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    /** 冗余索引列：'user' | 'assistant'（wire 判别值小写，与 Role 枚举 name().toLowerCase 对齐）。 */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    /** JSON blob: UserMessage | AssistantMessage。 */
    @Lob
    @Column(name = "data", nullable = false)
    private String data;

    @Column(name = "time_created")
    private Long timeCreated;

    /** 由 {@code trg_message_updated} 触发器兜底维护（DDL §7.1）。 */
    @Column(name = "time_updated")
    private Long timeUpdated;

    protected MessageRow() {
        // JPA
    }

    public static MessageRow newRow(String id, String sessionId, String role, String data,
                                    Long timeCreated, Long timeUpdated) {
        MessageRow row = new MessageRow();
        row.id = id;
        row.sessionId = sessionId;
        row.role = role;
        row.data = data;
        row.timeCreated = timeCreated;
        row.timeUpdated = timeUpdated;
        return row;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public Long getTimeCreated() { return timeCreated; }
    public void setTimeCreated(Long timeCreated) { this.timeCreated = timeCreated; }

    public Long getTimeUpdated() { return timeUpdated; }
    public void setTimeUpdated(Long timeUpdated) { this.timeUpdated = timeUpdated; }
}
