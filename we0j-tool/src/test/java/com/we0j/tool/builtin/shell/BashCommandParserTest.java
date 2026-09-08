package com.we0j.tool.builtin.shell;

import com.we0j.tool.builtin.shell.BashCommandParser.ParsedCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** BashCommandParser（DDD §5.7.2）：切分 / env 前缀 / wrapper / 路径提取 / 重定向剥离 / 嵌套引号。 */
class BashCommandParserTest {

    private final BashCommandParser parser = new BashCommandParser();

    private static List<String> programs(String cmd, BashCommandParser p) {
        return p.parse(cmd).stream().map(ParsedCommand::program).toList();
    }

    @Test
    void splitsOnOperatorsKeepsQuotesIntact() {
        List<ParsedCommand> parsed = parser.parse("git add . && git commit -m 'x && y' ; ls | wc");
        assertThat(parsed).hasSize(4);
        assertThat(parsed).extracting(ParsedCommand::program).containsExactly("git", "git", "ls", "wc");
        // 引号内的 && 不切分：commit 的参数含 "x && y"
        assertThat(parsed.get(1).args()).containsExactly("commit", "-m", "x && y");
    }

    @Test
    void pipesOnBothSidesBecomeSubcommands() {
        assertThat(programs("cat f.txt | grep x", parser)).containsExactly("cat", "grep");
    }

    @Test
    void doesNotSplitInsideSubshellOrCommandSubstitution() {
        List<ParsedCommand> parsed = parser.parse("echo $(git log --oneline | head -5)");
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).program()).isEqualTo("echo");
        assertThat(parsed.get(0).hasSubshell()).isTrue();
    }

    @Test
    void nestedQuotesNotSplit() {
        List<ParsedCommand> parsed = parser.parse("echo \"she said 'hi' and \\$X\" ; ls");
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).program()).isEqualTo("echo");
    }

    @Test
    void envPrefixSkipped() {
        List<ParsedCommand> parsed = parser.parse("FOO=bar BAZ=qux python main.py");
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).program()).isEqualTo("python");
        assertThat(parsed.get(0).args()).containsExactly("main.py");
    }

    @Test
    void wrappersSkipped() {
        assertThat(programs("sudo npm run build", parser)).containsExactly("npm");
        assertThat(programs("nohup java -jar app.jar", parser)).containsExactly("java");
        assertThat(programs("nice -n 5 make", parser)).containsExactly("make");
        assertThat(programs("env PATH=/x ls", parser)).containsExactly("ls");
    }

    @Test
    void redirectsStrippedButFlagged(@TempDir Path tmp) throws IOException {
        List<ParsedCommand> parsed = parser.parse("echo hi > out.txt");
        assertThat(parsed).hasSize(1);
        ParsedCommand pc = parsed.get(0);
        assertThat(pc.program()).isEqualTo("echo");
        assertThat(pc.args()).containsExactly("hi");
        assertThat(pc.hasRedirect()).isTrue();

        ParsedCommand err = parser.parse("make 2>&1 >> build.log").get(0);
        assertThat(err.program()).isEqualTo("make");
        assertThat(err.args()).isEmpty();
        assertThat(err.hasRedirect()).isTrue();
    }

    @Test
    void emptySegmentsFiltered() {
        assertThat(programs("echo a && && ; echo b", parser)).containsExactly("echo", "echo");
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse("&&")).isEmpty();
    }

    @Test
    void pathArgumentsExtractedForPathAwarePrograms(@TempDir Path tmp) throws IOException {
        Path f = Files.createFile(tmp.resolve("victim.txt"));
        List<ParsedCommand> parsed = parser.parse("rm \"" + f + "\"");
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).pathArguments())
                .anySatisfy(p -> assertThat(p.toAbsolutePath().normalize().toString())
                        .isEqualTo(f.toAbsolutePath().normalize().toString()));
        // 非路径敏感命令不提取
        assertThat(programs("grep pattern " + f, parser)).containsExactly("grep");
        assertThat(parser.parse("grep pattern " + f).get(0).hasPathArguments()).isFalse();
        // 不存在的路径不提取（避免误判）
        assertThat(parser.parse("cat " + tmp.resolve("nope.txt")).get(0).pathArguments()).isEmpty();
    }

    @Test
    void programBaseNameStripsPath() {
        assertThat(programs("/usr/bin/git status", parser)).containsExactly("git");
        assertThat(programs("./mvnw test", parser)).containsExactly("mvnw");
    }

    @Test
    void newlineSeparatesSegments() {
        assertThat(programs("cd src\ngit status", parser)).containsExactly("cd", "git");
    }
}
