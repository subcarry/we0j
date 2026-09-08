package com.we0j.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.we0j.tool.builtin.shell.BashCommandParser.ParsedCommand;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ARITY 表最长前缀匹配（DDD §5.7.2）：arity 保留参数数 + 尾部 " *" 语义边界。 */
class BashArityTableTest {

    private final BashArityTable table = new BashArityTable();

    private static ParsedCommand pc(String program, String... args) {
        return new ParsedCommand(program, List.of(args), List.of(), "", false, false);
    }

    @Test
    void gitKeepsOneSubcommand() {
        assertThat(table.prefixPattern(pc("git", "commit", "-m", "msg")))
                .isEqualTo("git commit *");
        assertThat(table.prefixPattern(pc("git", "push", "origin", "main")))
                .isEqualTo("git push *");
        assertThat(table.prefixPattern(pc("git")))
                .isEqualTo("git");                          // 无参：arity 截断后无剩余 → 不加 " *"
    }

    @Test
    void npmKeepsTwoWords() {
        assertThat(table.prefixPattern(pc("npm", "run", "build"))).isEqualTo("npm run build");
        assertThat(table.prefixPattern(pc("npm", "run", "build", "--prod")))
                .isEqualTo("npm run build");                // flags 不计入 parts
        assertThat(table.prefixPattern(pc("npm", "install", "lodash", "express")))
                .isEqualTo("npm install lodash *");         // parts=[npm,install,lodash,express] key=npm arity2 keep3 → 有剩余
    }

    @Test
    void zeroArityProgramsCollapseToPrefixStar() {
        assertThat(table.prefixPattern(pc("rm", "-rf", "build"))).isEqualTo("rm *");
        assertThat(table.prefixPattern(pc("rm"))).isEqualTo("rm");
        assertThat(table.prefixPattern(pc("curl", "https://x"))).isEqualTo("curl *");
        assertThat(table.prefixPattern(pc("ls", "-la"))).isEqualTo("ls");   // 全 flags → 无剩余
    }

    @Test
    void longestPrefixKeyWins() {
        // "mvn" arity1；"./gradlew" arity1
        assertThat(table.prefixPattern(pc("mvn", "clean", "install"))).isEqualTo("mvn clean *");
        assertThat(table.prefixPattern(pc("./gradlew", "build"))).isEqualTo("./gradlew build");
        assertThat(table.prefixPattern(pc("docker", "run", "-d", "nginx")))
                .isEqualTo("docker run nginx");             // docker arity2 → keep 3 parts，无剩余
        assertThat(table.prefixPattern(pc("kubectl", "get", "pods")))
                .isEqualTo("kubectl get pods");
    }

    @Test
    void unknownProgramFallsBackLoosest() {
        assertThat(table.prefixPattern(pc("foobar", "a", "b"))).isEqualTo("foobar *");
        assertThat(table.prefixPattern(pc("foobar"))).isEqualTo("foobar *");
    }

    @Test
    void partsCapAtFourForPrefixLookup() {
        // uv arity2: parts=[uv, pip, install, pkg]（limit 3）→ key "uv pip install" 无 → "uv pip" 无 → "uv" arity2 keep3 → "uv pip install *"
        assertThat(table.prefixPattern(pc("uv", "pip", "install", "requests")))
                .isEqualTo("uv pip install *");
    }
}
