package com.we0j.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * {@code part} 表行 —— 流式更新最小粒度的薄壳表（DDD §4.5.1 / §7.1，FR-09）。
 *
 * <p>{@code data} 承载 {@code Part} sealed 多态 JSON blob（判别字段 {@code type}）。
 * 列侧冗余 {@code type} 索引列：「查所有未完成 tool part」（悬挂扫描 NFR-03）
 * 与「按会话查 text part 拼装历史」无需反序列化全部 blob。
 *
 * <p>写入路径见 {@code PartWriter}（ON CONFLICT upsert）与 {@code PartWriteThrottler}（节流，NFR-02）。
 */
@Entity
@Table(name = "part", indexes = {
        @Index(name = "idx_part_message", columnList = "message_id"),
        @Index(name = "idx_part_session", columnList = "session_id"),
        @Index(name = "idx_part_session_created", columnList = "session_id,time_created"),
        @Index(name = "idx_part_session_type", columnList = "session_id,type"),
        @Index(name = "idx_part_message_type", columnList = "message_id,type")
})
public class PartRow {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    /** 冗余索引列：'text' | 'reasoning' | 'tool' | 'step-start' | …（对齐 @JsonSubTypes name）。 */
    @Column(name = "type", nullable = false, length = 24)
    private String type;

    /** JSON blob: Part 多态。 */
    @Lob
    @Column(name = "data", nullable = false)
    private String data;

    @Column(name = "time_created")
    private Long timeCreated;

    /** 由 {@code trg_part_updated} 触发器兜底维护（DDL §7.1）。 */
    @Column(name = "time_updated")
    private Long timeUpdated;

    protected PartRow() {
        // JPA
    }

    public static PartRow newRow(String id, String messageId, String sessionId, String type, String data,
                                 Long timeCreated, Long timeUpdated) {
        PartRow row = new PartRow();
        row.id = id;
        row.messageId = messageId;
        row.sessionId = sessionId;
        row.type = type;
        row.data = data;
        row.timeCreated = timeCreated;
        row.timeUpdated = timeUpdated;
        return row;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public Long getTimeCreated() { return timeCreated; }
    public void setTimeCreated(Long timeCreated) { this.timeCreated = timeCreated; }

    public Long getTimeUpdated() { return timeUpdated; }
    public void setTimeUpdated(Long timeUpdated) { this.timeUpdated = timeUpdated; }
}
