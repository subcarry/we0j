package com.we0j.tool.builtin.search;

import com.we0j.tool.builtin.search.GlobResolver.NormalizedGlob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** GlobResolver：绝对/相对 glob 规范化（首个含通配段之前为 baseDir）。 */
class GlobResolverTest {

    private final GlobResolver resolver = new GlobResolver();

    @Test
    void relativeGlobDefaultsToWorkdir(@TempDir Path workdir) {
        NormalizedGlob g = resolver.normalize("**/*.ts", workdir);
        assertThat(g.pattern()).isEqualTo("**/*.ts");
        assertThat(g.baseDir()).isEqualTo(workdir.toAbsolutePath().normalize());
    }

    @Test
    void absoluteGlobSplitsAtFirstWildcard(@TempDir Path workdir) {
        String base = workdir.toAbsolutePath().toString().replace('\\', '/');
        NormalizedGlob g = resolver.normalize(base + "/src/**/*.java", workdir);
        assertThat(g.pattern()).isEqualTo("**/*.java");
        assertThat(g.baseDir()).isEqualTo(workdir.resolve("src").toAbsolutePath().normalize());
    }

    @Test
    void relativeSegmentBeforeWildcardJoinsWorkdir(@TempDir Path workdir) {
        NormalizedGlob g = resolver.normalize("src/main/*.kt", workdir);
        assertThat(g.pattern()).isEqualTo("*.kt");
        assertThat(g.baseDir()).isEqualTo(workdir.resolve("src/main").toAbsolutePath().normalize());
    }

    @Test
    void noWildcardTreatsLastSegmentAsPattern(@TempDir Path workdir) {
        NormalizedGlob g = resolver.normalize(workdir.resolve("readme.md").toString(), workdir);
        assertThat(g.pattern()).isEqualTo("readme.md");
        assertThat(g.baseDir()).isEqualTo(workdir.toAbsolutePath().normalize());
    }

    @Test
    void backslashInputNormalized(@TempDir Path workdir) {
        boolean win = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        String base = win
                ? workdir.resolve("test").toString().replace('/', '\\') + "\\**\\*.java"
                : workdir.resolve("test").toString() + "/**/*.java";
        NormalizedGlob g = resolver.normalize(base, workdir);
        assertThat(g.pattern()).isEqualTo("**/*.java");
        assertThat(g.baseDir()).isEqualTo(workdir.resolve("test").toAbsolutePath().normalize());
    }
}
