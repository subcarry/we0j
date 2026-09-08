package com.we0j.tool.permission;

import com.we0j.common.util.PathSafety;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 路径 → 权限 pattern 解析（FR-082）：
 * 项目内路径走相对展示 pattern（短路，不产生外部目录权限）；
 * 外部路径产生目录树 pattern（{@code <abs>/**}）交由 EXTERNAL_DIRECTORY 权限把关。
 * 目录树 pattern 委托 common {@link PathSafety}，勿在此重写规则。
 */
@Component
public final class PermissionPatternResolver {

    /** 路径是否在工作目录内（短路判定：项目内路径不触发 EXTERNAL_DIRECTORY）。 */
    public boolean isProjectPath(Path p, Path workdir) {
        return PathSafety.isInside(p, workdir);
    }

    /**
     * 单个路径文件工具的 patterns 集合：
     * <ul>
     *   <li>项目内 → [相对 workdir 的展示路径]（统一 '/' 分隔）</li>
     *   <li>项目外 → [目录树 pattern]（{@code /abs/path/**}）</li>
     * </ul>
     */
    public List<String> patternsFor(Path p, Path workdir) {
        Path abs = p.toAbsolutePath().normalize();
        if (isProjectPath(abs, workdir)) {
            return List.of(PathSafety.relative(abs, workdir));
        }
        return List.of(directoryTreePattern(abs));
    }

    /** 目录树权限 pattern（委托 PathSafety，FR-082）。 */
    public String directoryTreePattern(Path p) {
        return PathSafety.directoryTreePattern(p);
    }
}
