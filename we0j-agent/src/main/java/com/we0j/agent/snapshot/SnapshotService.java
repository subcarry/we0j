package com.we0j.agent.snapshot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 快照与回滚 SPI（DDD §5.10.1，FR-10）。
 *
 * <p>语义基于 shadow git（独立 {@code --git-dir}，不污染用户仓库）：
 * <ul>
 *   <li>{@link #track(String)}：{@code git add -A} + {@code write-tree}，返回 tree hash（<b>不创建 commit</b>）；
 *       失败返回 {@link Optional#empty()}，<b>不抛异常</b>——快照失败不阻断 Loop（FR-10 降级约束）。</li>
 *   <li>{@link #patch(String, String)}：相对某 tree hash 的变更文件列表。</li>
 *   <li>{@link #diffFull(String, String, String)}：两个 tree hash 间的完整 diff（name-status + numstat + before/after 全文）。</li>
 *   <li>{@link #restore(String, String)}：{@code read-tree} + {@code checkout-index -a -f}，整体恢复到快照。</li>
 *   <li>{@link #revert(String, List, String)}：逐文件 {@code checkout <hash> -- <file>}；
 *       文件不在目标快照中（本次新增）→ 删除。</li>
 *   <li>{@link #available()}：git 缺失或初始化失败 → false（禁用快照但不阻断 Loop）。</li>
 * </ul>
 *
 * <p>除 {@code track} 外，用户发起的操作（patch/diffFull/restore/revert）失败时抛
 * {@link com.we0j.common.exception.SnapshotException}，以便 UI 侧反馈。
 */
public interface SnapshotService {

    /** git add -A + write-tree，返回 tree hash（不创建 commit）。失败返回 empty，不抛。 */
    Optional<String> track(String sessionId);

    /** 相对某 tree hash 的变更文件列表（worktree 视角）。 */
    List<String> patch(String sessionId, String treeHash);

    /** 两个 tree hash 之间的完整 diff（name-status + numstat + before/after 全文）。 */
    FullDiff diffFull(String sessionId, String hash1, String hash2);

    /** read-tree + checkout-index -a -f：把整个工作区恢复到 treeHash。 */
    void restore(String sessionId, String treeHash);

    /** 逐文件 checkout；文件不在快照中则删除。 */
    void revert(String sessionId, List<String> files, String treeHash);

    /** 是否可用（git 缺失或初始化失败 → false，禁用快照但不阻断 Loop）。 */
    boolean available();

    /**
     * 两 tree hash 的完整 diff（DDD §5.10.1）。
     *
     * @param nameStatus name-status 解析结果（状态 + 路径；rename 时 path 取新路径）
     * @param numstat    numstat 解析结果（增/删行数；二进制为 0）
     * @param contents   path → before/after 全文（缺失侧为 null）
     */
    record FullDiff(List<NameStatus> nameStatus, List<Numstat> numstat,
                    Map<String, FileBeforeAfter> contents) {

        public FullDiff {
            nameStatus = nameStatus == null ? List.of() : List.copyOf(nameStatus);
            numstat = numstat == null ? List.of() : List.copyOf(numstat);
            contents = contents == null ? Map.of() : Map.copyOf(contents);
        }

        public static FullDiff empty() {
            return new FullDiff(List.of(), List.of(), Map.of());
        }
    }

    /** 单文件 before/after 全文（缺失侧为 null；rename 的旧侧按旧路径另行取值时由调用方处理）。 */
    record FileBeforeAfter(char status, String before, String after) {}

    /**
     * git diff --name-status 的一行。
     *
     * @param status 原始状态串（"A"/"M"/"D"/"R100" 等）
     * @param path   文件路径（git 规范：'/' 分隔；rename 取新路径）
     */
    record NameStatus(String status, String path) {
        /** 状态首字符（A/M/D/R/C...）。 */
        public char statusCode() {
            return status == null || status.isEmpty() ? '?' : status.charAt(0);
        }
    }

    /**
     * git diff --numstat 的一行。
     *
     * @param path      文件路径
     * @param additions 新增行数（二进制文件记 0）
     * @param deletions 删除行数（二进制文件记 0）
     */
    record Numstat(String path, int additions, int deletions) {}
}
