package com.we0j.infra.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** ProjectId：SHA-256 前 16 位小写 hex；同目录稳定、不同目录不同（FR-14 / DDD §7.1）。 */
class ProjectIdTest {

    private static final Pattern HEX16 = Pattern.compile("^[0-9a-f]{16}$");

    @Test
    void isStableForSameDirectory() {
        Path a = Path.of("D:", "somewhere", "repo");
        String first = ProjectId.of(a);
        String second = ProjectId.of(a);
        assertEquals(first, second, "同目录多次计算应一致");
    }

    @Test
    void normalizesEquivalentPaths() {
        // toAbsolutePath().normalize() 后等价的路径应得到同一 ID
        String withDot = ProjectId.of(Path.of("D:", "somewhere", ".", "repo"));
        String plain = ProjectId.of(Path.of("D:", "somewhere", "repo"));
        assertEquals(withDot, plain, "含 ./ 的等价路径应归一化");
    }

    @Test
    void differsForDifferentDirectories() {
        String id1 = ProjectId.of(Path.of("D:", "project-one"));
        String id2 = ProjectId.of(Path.of("D:", "project-two"));
        assertNotEquals(id1, id2, "不同目录应得到不同 ID");
    }

    @Test
    void isLowercaseHex16() {
        assertTrue(HEX16.matcher(ProjectId.of(Path.of("D:", "any", "path"))).matches(),
                "应为 16 位小写 hex");
    }
}
