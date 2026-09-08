package com.we0j.common.util;

import com.we0j.common.exception.ToolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PathSafety 路径安全（FR-071/073/082）用例。 */
class PathSafetyTest {

    @Test
    void resolveRelativePathInsideWorkdir(@TempDir Path workdir) {
        Path resolved = PathSafety.resolve("src/main/Foo.java", workdir);
        assertThat(resolved).isEqualTo(workdir.resolve("src/main/Foo.java").normalize());
        assertThat(PathSafety.isInside(resolved, workdir)).isTrue();
    }

    @Test
    void resolveTraversalThrows(@TempDir Path workdir) {
        assertThatThrownBy(() -> PathSafety.resolve("../evil", workdir))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("traversal");
    }

    @Test
    void resolveAbsolutePathIsAllowed(@TempDir Path outside) {
        String raw = outside.resolve("some/file.txt").toString();
        Path resolved = PathSafety.resolve(raw, outside.getParent());
        assertThat(resolved).isEqualTo(outside.resolve("some/file.txt").normalize());
    }

    @Test
    void isInsideTrueAndFalse(@TempDir Path workdir) {
        assertThat(PathSafety.isInside(workdir.resolve("a/b"), workdir)).isTrue();
        // workdir 本身算在内部；workdir 的父目录才算外部
        assertThat(PathSafety.isInside(workdir, workdir)).isTrue();
        assertThat(PathSafety.isInside(workdir.getParent(), workdir)).isFalse();
    }

    @Test
    void relativeUsesForwardSlashes(@TempDir Path workdir) {
        assertThat(PathSafety.relative(workdir.resolve("a").resolve("b.txt"), workdir))
                .isEqualTo("a/b.txt");
    }

    @Test
    void directoryTreePatternEndsWithDoubleStar(@TempDir Path workdir) {
        assertThat(PathSafety.directoryTreePattern(workdir)).endsWith("/**");
    }

    @Test
    void realpathFallsBackForMissingPath(@TempDir Path workdir) {
        Path missing = workdir.resolve("does-not-exist");
        assertThatCode(() -> PathSafety.realpath(missing)).doesNotThrowAnyException();
        assertThat(PathSafety.realpath(missing)).isEqualTo(missing.toAbsolutePath().normalize());
    }
}
