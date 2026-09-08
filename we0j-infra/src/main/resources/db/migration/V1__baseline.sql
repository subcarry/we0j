-- ============================================================================
-- We0J baseline schema
-- SQLite 3.x；Hibernate ddl-auto=validate，所有变更必须走 Flyway 版本化迁移
-- 设计原则（保留自原项目）：message / part 为「薄壳表 + JSON blob」，
--   Part 类型演进零 migration 成本；仅冗余高频查询字段为索引列。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- session：会话主表（强类型列）
-- ----------------------------------------------------------------------------
CREATE TABLE session (
    id                 TEXT    NOT NULL PRIMARY KEY,          -- ULID(26)，时间有序
    project_id         TEXT    NOT NULL,                      -- workdir 绝对路径 SHA-256 前 16 位
    parent_id          TEXT,                                  -- 非空 = 子 Agent 会话
    name               TEXT,                                  -- 用户可命名的会话别名
    directory          TEXT    NOT NULL,                      -- 工作目录绝对路径
    title              TEXT    NOT NULL,                      -- 展示标题（首轮后异步语义化）
    version            TEXT    NOT NULL,                      -- 创建时的 We0J 版本
    share_url          TEXT,
    -- 本轮 diff 摘要（异步回填，供 /rewind 面板）
    summary_additions  INTEGER,
    summary_deletions  INTEGER,
    summary_files      INTEGER,
    summary_diffs      TEXT,                                  -- JSON: List<FileDiff>
    -- 回滚记录（FR-102）
    revert             TEXT,                                  -- JSON: RevertRecord
    -- ★ 架构改进：会话运行时状态落库，保证 resume 完整性（FR-013）
    runtime_state      TEXT,                                  -- JSON: RuntimeState
    -- 时间戳（epoch millis，避免 SQLite 日期函数差异）
    time_created       INTEGER,
    time_updated       INTEGER,
    time_compacting    INTEGER,                               -- 非空 = 压缩进行中
    time_archived      INTEGER,
    is_incognito       INTEGER NOT NULL DEFAULT 0,            -- 1 = 内部子会话，不进 /resume
    CONSTRAINT fk_session_parent FOREIGN KEY (parent_id) REFERENCES session(id) ON DELETE SET NULL
);

CREATE INDEX idx_session_project          ON session (project_id);
CREATE INDEX idx_session_parent           ON session (parent_id);
CREATE INDEX idx_session_project_updated  ON session (project_id, time_updated DESC);
CREATE INDEX idx_session_archived         ON session (time_archived);
-- 部分唯一索引：name 非空时 (project_id, name) 唯一（对齐原项目 sqlite_where）
CREATE UNIQUE INDEX idx_session_project_name_unique
    ON session (project_id, name) WHERE name IS NOT NULL;

-- ----------------------------------------------------------------------------
-- message：消息薄壳表
-- ----------------------------------------------------------------------------
CREATE TABLE message (
    id            TEXT    NOT NULL PRIMARY KEY,
    session_id    TEXT    NOT NULL,
    role          TEXT    NOT NULL,                            -- 'user' | 'assistant'（冗余索引列）
    data          TEXT    NOT NULL,                            -- JSON blob: UserMessage | AssistantMessage
    time_created  INTEGER,
    time_updated  INTEGER,
    CONSTRAINT fk_message_session FOREIGN KEY (session_id) REFERENCES session(id) ON DELETE CASCADE
);

CREATE INDEX idx_message_session          ON message (session_id);
CREATE INDEX idx_message_session_created  ON message (session_id, time_created);
CREATE INDEX idx_message_session_role     ON message (session_id, role);

-- ----------------------------------------------------------------------------
-- part：消息片段薄壳表（流式更新的最小粒度）
-- ----------------------------------------------------------------------------
CREATE TABLE part (
    id            TEXT    NOT NULL PRIMARY KEY,
    message_id    TEXT    NOT NULL,
    session_id    TEXT    NOT NULL,
    type          TEXT    NOT NULL,                            -- 'text'|'reasoning'|'tool'|'step-start'|…
    data          TEXT    NOT NULL,                            -- JSON blob: Part 多态
    time_created  INTEGER,
    time_updated  INTEGER,
    CONSTRAINT fk_part_message FOREIGN KEY (message_id) REFERENCES message(id) ON DELETE CASCADE,
    CONSTRAINT fk_part_session FOREIGN KEY (session_id) REFERENCES session(id) ON DELETE CASCADE
);

CREATE INDEX idx_part_message           ON part (message_id);
CREATE INDEX idx_part_session           ON part (session_id);
CREATE INDEX idx_part_session_created   ON part (session_id, time_created);
CREATE INDEX idx_part_session_type      ON part (session_id, type);
-- 启动时扫描修复悬挂 Part（NFR-03）：WHERE type='tool' 且 data 含 pending/running
CREATE INDEX idx_part_message_type      ON part (message_id, type);

-- ----------------------------------------------------------------------------
-- 触发器：自动维护 time_updated（减少应用层遗漏）
-- ----------------------------------------------------------------------------
CREATE TRIGGER trg_session_updated AFTER UPDATE ON session
    FOR EACH ROW WHEN NEW.time_updated IS OLD.time_updated
BEGIN
    UPDATE session SET time_updated = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE id = NEW.id;
END;

CREATE TRIGGER trg_message_updated AFTER UPDATE ON message
    FOR EACH ROW WHEN NEW.time_updated IS OLD.time_updated
BEGIN
    UPDATE message SET time_updated = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE id = NEW.id;
END;

CREATE TRIGGER trg_part_updated AFTER UPDATE ON part
    FOR EACH ROW WHEN NEW.time_updated IS OLD.time_updated
BEGIN
    UPDATE part SET time_updated = CAST(strftime('%s','now') AS INTEGER) * 1000 WHERE id = NEW.id;
END;

-- ----------------------------------------------------------------------------
-- 视图：便捷查询（不参与写入）
-- ----------------------------------------------------------------------------
CREATE VIEW v_session_latest AS
SELECT s.id, s.project_id, s.title, s.directory, s.time_updated, s.summary_files,
       (SELECT COUNT(*) FROM message m WHERE m.session_id = s.id) AS message_count
FROM session s
WHERE s.is_incognito = 0 AND s.time_archived IS NULL;

-- 未终结的 tool part（启动修复扫描用）
CREATE VIEW v_dangling_tool_parts AS
SELECT p.id, p.session_id, p.message_id, p.data
FROM part p
WHERE p.type = 'tool'
  AND (p.data LIKE '%"status":"pending"%' OR p.data LIKE '%"status":"running"%');
