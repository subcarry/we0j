package com.we0j.cli.slash;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.agent.bootstrap.RuntimeBootstrap;
import com.we0j.common.domain.permission.PermissionMode;
import com.we0j.infra.path.DirectoryLayout;
import com.we0j.llm.spi.ModelCard;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SlashCommandRegistry 路由测试（FR-122 验收子集）：
 * /help /exit(/quit) /status 路由正确、未知命令报错、参数切分与别名表。
 *
 * <p>用真 RuntimeBootstrap（@TempDir sqlite + DirectoryLayout 覆盖，同 we0j-agent 测试惯例），
 * 但不发起任何模型调用（无 prompt 轮），FakeModelProvider 非必需。
 */
class SlashCommandRegistryTest {

    @TempDir
    Path workdir;

    private RuntimeBootstrap bs;
    private ReplSession session;
    private final SlashCommandRegistry registry = new SlashCommandRegistry();

    @BeforeEach
    void boot() {
        DirectoryLayout.setUserHomeOverride(workdir.resolve("home"));
        RuntimeBootstrap.Options opts = new RuntimeBootstrap.Options();
        opts.modelCardOverride = ModelCard.basic("fake", "fake-test");
        bs = RuntimeBootstrap.init(workdir, opts);
        String sid = bs.sessions().create(workdir, null, null).getId();
        session = new ReplSession(bs, workdir, sid, PermissionMode.BYPASS);
    }

    @AfterEach
    void close() {
        if (bs != null) bs.close();
        DirectoryLayout.setUserHomeOverride(null);
    }

    // ── /help ───────────────────────────────────────────────────────────────

    @Test
    void helpListsBuiltinCommands() {
        SlashResult r = registry.execute("/help", session);
        assertThat(r).isInstanceOf(SlashResult.Handled.class);
        String msg = ((SlashResult.Handled) r).message();
        assertThat(msg)
                .contains("/compact").contains("/rewind").contains("/status")
                .contains("/todos").contains("/tasks").contains("/model").contains("/mcp")
                .contains("/usage").contains("/new").contains("/exit");
        // 别名同样路由到 Help
        assertThat(registry.execute("/h", session)).isInstanceOf(SlashResult.Handled.class);
        assertThat(registry.execute("/?", session)).isInstanceOf(SlashResult.Handled.class);
    }

    // ── /exit ───────────────────────────────────────────────────────────────

    @Test
    void exitAndAliasesRouteToExit() {
        assertThat(registry.execute("/exit", session)).isInstanceOf(SlashResult.Exit.class);
        assertThat(registry.execute("/quit", session)).isInstanceOf(SlashResult.Exit.class);
        assertThat(registry.execute("/EXIT", session)).isInstanceOf(SlashResult.Exit.class);   // 大小写不敏感
    }

    // ── /status ─────────────────────────────────────────────────────────────

    @Test
    void statusReportsSessionFacts() {
        SlashResult r = registry.execute("/status", session);
        assertThat(r).isInstanceOf(SlashResult.Handled.class);
        String msg = ((SlashResult.Handled) r).message();
        assertThat(msg)
                .contains(session.sessionId())
                .contains("status")
                .contains("usage")
                .contains("BYPASS");                       // 权限模式（构造时应用）
    }

    // ── 未知命令 / 空命令 ───────────────────────────────────────────────────

    @Test
    void unknownCommandErrors() {
        SlashResult r = registry.execute("/definitely-not-a-command arg", session);
        assertThat(r).isInstanceOf(SlashResult.Error.class);
        assertThat(((SlashResult.Error) r).message()).contains("未知命令").contains("definitely-not-a-command");
        assertThat(registry.execute("/", session)).isInstanceOf(SlashResult.Error.class);
    }

    // ── 参数切分 / 其他路由健全性 ───────────────────────────────────────────

    @Test
    void quotedArgumentsSurviveTokenization() {
        // /compact 携带指令：不发起轮（忙=false），空历史 → NONE → “无需压缩”，验证参数路径不抛错
        SlashResult r = registry.execute("/compact \"保留 API 变更细节\"", session);
        assertThat(r).isInstanceOf(SlashResult.Handled.class);
        assertThat(((SlashResult.Handled) r).message()).contains("无需压缩");
    }

    @Test
    void mcpPlaceholderAndRewindEmptyPanel() {
        assertThat(((SlashResult.Handled) registry.execute("/mcp", session)).message()).contains("MCP");
        SlashResult rw = registry.execute("/rewind", session);
        assertThat(rw).isInstanceOf(SlashResult.Handled.class);
        assertThat(((SlashResult.Handled) rw).message()).contains("无可回滚锚点");
    }

    @Test
    void newCommandSwapsSessionId() {
        String old = session.sessionId();
        SlashResult r = registry.execute("/new", session);
        assertThat(r).isInstanceOf(SlashResult.Handled.class);
        assertThat(session.sessionId()).isNotEqualTo(old);
    }

    @Test
    void registryExposesNamedCommandsAndPermissionParsing() {
        assertThat(registry.find("help")).isPresent();
        assertThat(registry.find("exit").orElseThrow().aliases()).contains("quit");
        assertThat(registry.commands()).extracting(SlashCommand::name)
                .contains("help", "exit", "new", "compact", "rewind", "status",
                        "todos", "tasks", "model", "mcp", "usage", "cancel");
        assertThat(SlashCommandRegistry.parsePermissionMode("allow_once"))
                .contains(PermissionMode.ALLOW_ONCE);
        assertThat(SlashCommandRegistry.parsePermissionMode("nope")).isEmpty();
    }
}
