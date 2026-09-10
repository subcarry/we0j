package com.we0j.server.api;

import com.we0j.agent.session.SessionService;
import com.we0j.agent.skill.SkillService;
import com.we0j.agent.skill.SkillStore;
import com.we0j.common.domain.skill.SkillCard;
import com.we0j.infra.path.DirectoryLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Skills 可观测面（方案 docs/03 P0/P2）：当前生效快照 + watcher 状态 + 失败清单 + 手动刷新。
 *
 * <p>渲染纪律（缓存契约 C4/C5 的边界）：本端点面向人，<b>不进 prompt</b>；
 * location / failed / scannedAt 等 volatile 字段只在这里暴露。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService skills;
    private final SessionService sessions;
    private final Path projectRoot;

    public SkillController(SkillService skills, SessionService sessions, Path projectRoot) {
        this.skills = skills;
        this.sessions = sessions;
        this.projectRoot = projectRoot;
    }

    /** 单张卡片视图。source = global | project（按 location 前缀判定）。 */
    public record SkillView(String name, String description, String source, String location) {}

    /** 快照全景（P0）：skills + 元数据 + watcher 状态。 */
    public record SkillsView(String root, List<SkillView> skills, int total, String scannedAt,
                             boolean watcherRunning, List<String> watchedRoots, List<String> failed) {}

    /** 手动刷新结果（P2）。 */
    public record RefreshResult(int refreshedRoots, SkillsView snapshot) {}

    @GetMapping
    public SkillsView snapshot(@RequestParam(required = false) String sessionId) {
        return view(rootOf(sessionId));
    }

    @PostMapping("/refresh")
    public RefreshResult refresh(@RequestParam(required = false) String sessionId) {
        int n = skills.refreshAll();
        return new RefreshResult(n, view(rootOf(sessionId)));
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private Path rootOf(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                return Path.of(sessions.requireRow(sessionId).getDirectory());
            } catch (RuntimeException e) {
                return projectRoot;                              // 未知会话：退化启动根
            }
        }
        return projectRoot;
    }

    private SkillsView view(Path root) {
        // 读面顺带 drain 脏标记：无对话进行时面板也能实时反映热加载结果；
        // rescanAll 覆盖所有已知根，与 Loop 侧 drain 无丢更新竞态。
        skills.refreshIfPending(root);
        List<SkillCard> cards = skills.snapshotFor(root);
        SkillStore.ScanMeta meta = skills.scanMeta(root);
        String globalPrefix = DirectoryLayout.globalSkillsDir().toAbsolutePath().normalize().toString();
        List<SkillView> views = new ArrayList<>(cards.size());
        for (SkillCard c : cards) {
            boolean global = c.location() != null && c.location().startsWith(globalPrefix);
            views.add(new SkillView(c.name(), c.description(), global ? "global" : "project", c.location()));
        }
        List<String> watched = new ArrayList<>();
        for (Path p : skills.watchedRoots(root)) {
            watched.add(p.toString());
        }
        return new SkillsView(root == null ? null : root.toString(), List.copyOf(views), views.size(),
                meta.scannedAt().toString(), skills.watcherRunning(), List.copyOf(watched), meta.failed());
    }
}
