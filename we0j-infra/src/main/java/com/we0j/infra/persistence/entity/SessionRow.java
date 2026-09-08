package com.we0j.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * {@code session} 表行（DDD §4.5.1 / §7.1，FR-09）。
 *
 * <p>强类型主表：高频查询字段（project_id / time_updated / is_incognito）为真实列，
 * 结构化但低频演进的字段（summary_diffs / revert / runtime_state）以 JSON 文本 blob 存储。
 *
 * <p>与 DDL 的两处口径差异（以 {@code V1__baseline.sql} 为准）：
 * <ul>
 *   <li>{@code (project_id, name)} 唯一性是<b>部分</b>唯一索引（{@code WHERE name IS NOT NULL}），
 *       无法用 JPA {@code @UniqueConstraint} 表达，故不在注解中声明；</li>
 *   <li>{@code time_created} 等列在 DDL 中可空，实体侧仍按 NOT NULL 语义由应用层保证。</li>
 * </ul>
 *
 * <p>JPA 要求 protected 无参构造；业务代码用 {@link #newSession} 静态工厂 + setter。
 */
@Entity
@Table(name = "session", indexes = {
        @Index(name = "idx_session_project", columnList = "project_id"),
        @Index(name = "idx_session_parent", columnList = "parent_id"),
        @Index(name = "idx_session_project_updated", columnList = "project_id,time_updated"),
        @Index(name = "idx_session_archived", columnList = "time_archived")
})
public class SessionRow {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "parent_id")
    private String parentId;

    @Column(name = "name")
    private String name;

    @Column(name = "directory", nullable = false)
    private String directory;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "share_url")
    private String shareUrl;

    @Column(name = "summary_additions")
    private Integer summaryAdditions;

    @Column(name = "summary_deletions")
    private Integer summaryDeletions;

    @Column(name = "summary_files")
    private Integer summaryFiles;

    /** JSON: {@code List<FileDiff>}（DDD §7.2）。 */
    @Lob
    @Column(name = "summary_diffs")
    private String summaryDiffs;

    /** JSON: {@code RevertRecord}（FR-102）。 */
    @Lob
    @Column(name = "revert")
    private String revert;

    @Column(name = "time_created")
    private Long timeCreated;

    @Column(name = "time_updated")
    private Long timeUpdated;

    /** 非空 = 压缩进行中（FR-052）。 */
    @Column(name = "time_compacting")
    private Long timeCompacting;

    /** 非空 = 已归档，不进 v_session_latest（FR-015）。 */
    @Column(name = "time_archived")
    private Long timeArchived;

    /** 1 = 内部子会话，不进 /resume 列表（FR-01）。 */
    @Column(name = "is_incognito", nullable = false)
    private boolean incognito;

    /**
     * JSON: {@code RuntimeState} —— 权限运行时规则 / 已激活延迟工具 / agent 人格 / 权限模式。
     * ★ 架构改进：resume 完整性前提（FR-013）。
     */
    @Lob
    @Column(name = "runtime_state")
    private String runtimeState;

    protected SessionRow() {
        // JPA
    }

    /** 新会话工厂：填充 NOT NULL 列与时间戳，incognito 默认 false。 */
    public static SessionRow newSession(String id, String projectId, String directory,
                                        String title, String version,
                                        long timeCreated, long timeUpdated) {
        SessionRow row = new SessionRow();
        row.id = id;
        row.projectId = projectId;
        row.directory = directory;
        row.title = title;
        row.version = version;
        row.timeCreated = timeCreated;
        row.timeUpdated = timeUpdated;
        row.incognito = false;
        return row;
    }

    /** 子 Agent 会话工厂（parent_id 非空 + incognito，FR-040）。 */
    public static SessionRow newChildSession(String id, String projectId, String parentId, String name,
                                             String directory, String title, String version,
                                             long timeCreated, long timeUpdated) {
        SessionRow row = newSession(id, projectId, directory, title, version, timeCreated, timeUpdated);
        row.parentId = parentId;
        row.name = name;
        row.incognito = true;
        return row;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getShareUrl() { return shareUrl; }
    public void setShareUrl(String shareUrl) { this.shareUrl = shareUrl; }

    public Integer getSummaryAdditions() { return summaryAdditions; }
    public void setSummaryAdditions(Integer summaryAdditions) { this.summaryAdditions = summaryAdditions; }

    public Integer getSummaryDeletions() { return summaryDeletions; }
    public void setSummaryDeletions(Integer summaryDeletions) { this.summaryDeletions = summaryDeletions; }

    public Integer getSummaryFiles() { return summaryFiles; }
    public void setSummaryFiles(Integer summaryFiles) { this.summaryFiles = summaryFiles; }

    public String getSummaryDiffs() { return summaryDiffs; }
    public void setSummaryDiffs(String summaryDiffs) { this.summaryDiffs = summaryDiffs; }

    public String getRevert() { return revert; }
    public void setRevert(String revert) { this.revert = revert; }

    public Long getTimeCreated() { return timeCreated; }
    public void setTimeCreated(Long timeCreated) { this.timeCreated = timeCreated; }

    public Long getTimeUpdated() { return timeUpdated; }
    public void setTimeUpdated(Long timeUpdated) { this.timeUpdated = timeUpdated; }

    public Long getTimeCompacting() { return timeCompacting; }
    public void setTimeCompacting(Long timeCompacting) { this.timeCompacting = timeCompacting; }

    public Long getTimeArchived() { return timeArchived; }
    public void setTimeArchived(Long timeArchived) { this.timeArchived = timeArchived; }

    public boolean isIncognito() { return incognito; }
    public void setIncognito(boolean incognito) { this.incognito = incognito; }

    public String getRuntimeState() { return runtimeState; }
    public void setRuntimeState(String runtimeState) { this.runtimeState = runtimeState; }
}
