package com.we0j.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Wildcards 通配符匹配（FR-081）用例。 */
class WildcardsTest {

    @Test
    void starMatchesAnything() {
        assertThat(Wildcards.match("*", "Read")).isTrue();
        assertThat(Wildcards.match("*", "any/tool/value")).isTrue();
    }

    @Test
    void prefixWildcardMatchesNestedPath() {
        assertThat(Wildcards.match("src/*", "src/main/Foo.java")).isTrue();
    }

    @Test
    void spaceSeparatedCommandPattern() {
        assertThat(Wildcards.match("git *", "git push origin main")).isTrue();
        assertThat(Wildcards.match("git *", "hg push")).isFalse();
    }

    @Test
    void exactNameMatchesItself() {
        assertThat(Wildcards.match("Read", "Read")).isTrue();
        assertThat(Wildcards.match("Read", "Write")).isFalse();
    }

    @Test
    void questionMarkMatchesSingleChar() {
        assertThat(Wildcards.match("a?c", "abc")).isTrue();
        assertThat(Wildcards.match("a?c", "ac")).isFalse();
        assertThat(Wildcards.match("a?c", "abbc")).isFalse();
    }

    @Test
    void caseInsensitiveOnWindows() {
        // 测试运行在 Windows 上：pattern 与 value 大小写不敏感
        assertThat(Wildcards.match("READ", "Read")).isTrue();
    }

    @Test
    void backslashPatternNormalizedToSlash() {
        assertThat(Wildcards.match("src\\main", "src/main")).isTrue();
    }

    @Test
    void regexMetaCharsAreEscaped() {
        // '.' 是字面点号，不是任意字符
        assertThat(Wildcards.match("a.b", "aXb")).isFalse();
        assertThat(Wildcards.match("a.b", "a.b")).isTrue();
    }
}
