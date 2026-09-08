package com.we0j.agent.skill;

import com.we0j.common.domain.skill.SkillCard;
import com.we0j.tool.spi.SkillBodyExpander;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.infra.path.ProjectId;
import java.nio.file.Path;

/**
 * {{WE0J_*}} 路径占位符展开（DDD §5.11 占位符表，FR-080）。
 *
 * <table border="1">
 *   <caption>占位符</caption>
 *   <tr><th>占位符</th><th>展开为</th></tr>
 *   <tr><td>{{WE0J_SKILL_DIR}}</td><td>skill 所在目录绝对路径</td></tr>
 *   <tr><td>{{WE0J_SKILL_FILE}}</td><td>SKILL.md 绝对路径</td></tr>
 *   <tr><td>{{WE0J_WORKDIR}}</td><td>当前项目根</td></tr>
 *   <tr><td>{{WE0J_HOME}}</td><td>~/.we0j</td></tr>
 *   <tr><td>{{WE0J_SESSION_ID}}</td><td>当前会话 id</td></tr>
 *   <tr><td>{{WE0J_PROJECT_DATA}}</td><td>~/.we0j/projects/&lt;projectId&gt;</td></tr>
 * </table>
 *
 * <p>实现 {@link SkillBodyExpander}（tool 侧 SPI）：we0j-tool 不能反向依赖本模块，
 * SkillTool 经该缝消费展开能力。
 */
public final class SkillTemplateExpander implements SkillBodyExpander {

    public static final String SKILL_DIR = "{{WE0J_SKILL_DIR}}";
    public static final String SKILL_FILE = "{{WE0J_SKILL_FILE}}";
    public static final String WORKDIR = "{{WE0J_WORKDIR}}";
    public static final String HOME = "{{WE0J_HOME}}";
    public static final String SESSION_ID = "{{WE0J_SESSION_ID}}";
    public static final String PROJECT_DATA = "{{WE0J_PROJECT_DATA}}";

    /** 按卡片展开正文（location = skill 目录，由 SkillScanner 写入）。 */
    @Override
    public String expand(SkillCard card, Path workdir, String sessionId) {
        if (card == null) return "";
        return expand(card.body(), Path.of(card.location() == null || card.location().isBlank()
                ? "." : card.location()), workdir, sessionId);
    }

    /** 低层入口：文本 + 显式 skill 目录（location 直指 SKILL.md 时也能派生两态）。 */
    public String expand(String text, Path skillDir, Path workdir, String sessionId) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;

        Path anchor = skillDir == null ? Path.of(".") : skillDir.toAbsolutePath().normalize();
        Path dir;
        Path file;
        String fileName = String.valueOf(anchor.getFileName());
        if ("SKILL.md".equalsIgnoreCase(fileName)) {
            file = anchor;
            dir = anchor.getParent() == null ? anchor : anchor.getParent();
        } else {
            dir = anchor;
            file = anchor.resolve("SKILL.md");
        }
        Path wd = workdir == null ? Path.of(".").toAbsolutePath().normalize()
                : workdir.toAbsolutePath().normalize();
        Path home = DirectoryLayout.userHome().toAbsolutePath().normalize();
        String projectData = DirectoryLayout.projectDataDir(ProjectId.of(wd))
                .toAbsolutePath().normalize().toString();

        String out = text;
        out = out.replace(SKILL_DIR, dir.toString());
        out = out.replace(SKILL_FILE, file.toString());
        out = out.replace(WORKDIR, wd.toString());
        out = out.replace(HOME, home.toString());
        out = out.replace(SESSION_ID, sessionId == null ? "" : sessionId);
        out = out.replace(PROJECT_DATA, projectData);
        return out;
    }
}
