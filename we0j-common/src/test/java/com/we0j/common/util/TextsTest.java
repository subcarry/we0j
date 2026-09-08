package com.we0j.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Texts 截断与空值安全用例。 */
class TextsTest {

    @Test
    void truncateReturnsAsIsWhenNotTooLong() {
        assertThat(Texts.truncate("hello", 10)).isEqualTo("hello");
        assertThat(Texts.truncate("hello", 5)).isEqualTo("hello");
    }

    @Test
    void truncateAppendsEllipsisAndKeepsTotalLength() {
        String result = Texts.truncate("abcdefghij", 4);
        assertThat(result).isEqualTo("abcd…");
        assertThat(result.length()).isLessThanOrEqualTo(4 + 1);
    }

    @Test
    void truncateNullReturnsEmpty() {
        assertThat(Texts.truncate(null, 5)).isEmpty();
    }

    @Test
    void nullSafeConvertsNullToEmpty() {
        assertThat(Texts.nullSafe(null)).isEmpty();
        assertThat(Texts.nullSafe("x")).isEqualTo("x");
    }
}
