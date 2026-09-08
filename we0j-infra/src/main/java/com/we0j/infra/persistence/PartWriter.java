package com.we0j.infra.persistence;

import com.we0j.common.domain.part.AgentPart;
import com.we0j.common.domain.part.CompactionPart;
import com.we0j.common.domain.part.FilePart;
import com.we0j.common.domain.part.PatchPart;
import com.we0j.common.domain.part.Part;
import com.we0j.common.domain.part.ReasoningPart;
import com.we0j.common.domain.part.RetryPart;
import com.we0j.common.domain.part.SnapshotPart;
import com.we0j.common.domain.part.StepFinishPart;
import com.we0j.common.domain.part.StepStartPart;
import com.we0j.common.domain.part.TextPart;
import com.we0j.common.domain.part.ToolPart;
import com.we0j.common.util.Jsons;
import java.time.Instant;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Repository;

/**
 * Part 原生 upsert 写入器（DDD §4.5.2，FR-09）。
 *
 * <p>Hibernate 6 不直接支持 SQLite {@code ON CONFLICT}，流式 Part 的高频小写走
 * JdbcTemplate 原生 SQL：{@code INSERT ... ON CONFLICT(id) DO UPDATE}。
 * {@code data} 列为 Part 多态 JSON blob（{@link Jsons#write}），{@code type} 冗余列
 * 由 sealed 类型 switch 得出（与 {@code @JsonSubTypes} 判别值一一对应）。
 *
 * <p>每次写经 {@link SqliteBusyRetry} 兜底 SQLITE_BUSY。
 */
@Repository
public class PartWriter {

    private static final String UPSERT = """
            INSERT INTO part (id, message_id, session_id, type, data, time_created, time_updated)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                data = excluded.data,
                type = excluded.type,
                time_updated = excluded.time_updated
            """;

    private final JdbcTemplate jdbc;
    private final SqliteBusyRetry retry;

    public PartWriter(JdbcTemplate jdbc, SqliteBusyRetry retry) {
        this.jdbc = jdbc;
        this.retry = retry;
    }

    /** 单行 upsert：{@code created} 仅首次插入生效（DO UPDATE 不改 time_created）。 */
    public void upsert(Part part, Instant created) {
        String data = Jsons.write(part);
        long now = Instant.now().toEpochMilli();
        long createdMillis = created.toEpochMilli();
        retry.run(() -> jdbc.update(UPSERT,
                part.id(), part.messageId(), part.sessionId(), typeOf(part),
                data, createdMillis, now));
    }

    /** 批量 upsert（历史迁移 / 批量回刷用）。 */
    public void upsertBatch(List<Part> parts, Instant created) {
        if (parts.isEmpty()) return;
        String[] data = new String[parts.size()];
        for (int i = 0; i < parts.size(); i++) data[i] = Jsons.write(parts.get(i));
        long createdMillis = created.toEpochMilli();
        long now = Instant.now().toEpochMilli();
        retry.run(() -> jdbc.batchUpdate(UPSERT, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Part p = parts.get(i);
                ps.setString(1, p.id());
                ps.setString(2, p.messageId());
                ps.setString(3, p.sessionId());
                ps.setString(4, typeOf(p));
                ps.setString(5, data[i]);
                ps.setLong(6, createdMillis);
                ps.setLong(7, now);
            }

            @Override
            public int getBatchSize() {
                return parts.size();
            }
        }));
    }

    /** Part 类型 → 冗余 type 列值（与 {@code @JsonSubTypes} name 对齐，DDD §7.1）。 */
    static String typeOf(Part part) {
        return switch (part) {
            case TextPart ignored -> "text";
            case ReasoningPart ignored -> "reasoning";
            case ToolPart ignored -> "tool";
            case FilePart ignored -> "file";
            case StepStartPart ignored -> "step-start";
            case StepFinishPart ignored -> "step-finish";
            case SnapshotPart ignored -> "snapshot";
            case PatchPart ignored -> "patch";
            case AgentPart ignored -> "agent";
            case CompactionPart ignored -> "compaction";
            case RetryPart ignored -> "retry";
        };
    }
}
