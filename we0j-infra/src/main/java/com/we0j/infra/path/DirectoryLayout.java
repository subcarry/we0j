package com.we0j.infra.path;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 用户级目录约定（FR-14 / DDD §7.1 文件布局表）。
 *
 * <p>{@code ~/.we0j} 下：settings.json、providers.json、history、bin/、skills/、
 * agents/、commands/、projects/&lt;projectId&gt;/...。
 *
 * <p><b>可测试性</b>：{@link #setUserHomeOverride(Path)} 允许测试注入替代 home 目录
 * （不依赖环境变量）；为 {@code null} 时取 {@code System.getProperty("user.home")/.we0j}。
 */
public final class DirectoryLayout {

    /** 测试注入的用户数据根目录覆盖；null = 使用真实 ~/.we0j。 */
    private static volatile Path userHomeOverride;

    public static void setUserHomeOverride(Path override) { userHomeOverride = override; }

    public static Path userHome() {
        Path o = userHomeOverride;
        if (o != null) return o;
        return Paths.get(System.getProperty("user.home")).resolve(".we0j");
    }

    /** 用户级配置：~/.we0j/settings.json */
    public static Path userSettings() { return userHome().resolve("settings.json"); }

    /** provider/model 基础设施清单：~/.we0j/providers.json */
    public static Path providersFile() { return userHome().resolve("providers.json"); }

    /** REPL 输入历史：~/.we0j/history（JLine FileHistory 独占） */
    public static Path historyFile() { return userHome().resolve("history"); }

    /** 随包分发二进制的解压目录：~/.we0j/bin（ripgrep 等，FR-121） */
    public static Path binDir() { return userHome().resolve("bin"); }

    public static Path globalSkillsDir() { return userHome().resolve("skills"); }

    public static Path globalAgentsDir() { return userHome().resolve("agents"); }

    public static Path globalCommandsDir() { return userHome().resolve("commands"); }

    /** 项目数据根：~/.we0j/projects */
    public static Path projectsRoot() { return userHome().resolve("projects"); }

    /** 单项目数据目录：~/.we0j/projects/&lt;projectId&gt;（DDD §7.1 {{WE0J_PROJECT_DATA}}） */
    public static Path projectDataDir(String projectId) { return projectsRoot().resolve(projectId); }

    /** 创建全部目录（存在即跳过）。history 是文件，不在此创建。 */
    public static void ensure() {
        try {
            for (Path dir : new Path[]{
                    userHome(), binDir(), globalSkillsDir(), globalAgentsDir(),
                    globalCommandsDir(), projectsRoot()}) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create ~/.we0j layout under " + userHome(), e);
        }
    }

    private DirectoryLayout() {}
}
