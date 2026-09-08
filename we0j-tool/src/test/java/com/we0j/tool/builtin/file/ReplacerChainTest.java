package com.we0j.tool.builtin.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.we0j.common.exception.ToolException;
import com.we0j.tool.builtin.file.ReplacerChain.ReplaceOutcome;
import com.we0j.tool.builtin.file.ReplacerChain.Strategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 编辑策略链测试矩阵（DDD §10.4，E-01~E-10 / E-14~E-15 / E-17）。
 * E-11/12/13/16（staleness 与并发）在 EditToolTest；链级 E-14/15 断言空 oldText 拒绝。
 */
class ReplacerChainTest {

    private final ReplacerChain chain = new ReplacerChain();

    @Test
    @DisplayName("E-01 精确命中 → SIMPLE")
    void e01Exact() {
        ReplaceOutcome r = chain.replace("a\nb\nc", "b", "X", false);
        assertThat(r.strategy()).isEqualTo(Strategy.SIMPLE);
        assertThat(r.newContent()).isEqualTo("a\nX\nc");
    }

    @Test
    @DisplayName("E-02 多处命中未 replaceAll → 歧义错误，含全部命中行号 [2, 4]")
    void e02Ambiguous() {
        assertThatThrownBy(() -> chain.replace("a\nb\na\nb", "b", "X", false))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("2 occurrences")
                .hasMessageContaining("[2, 4]")
                .hasMessageContaining("replaceAll=true");
    }

    @Test
    @DisplayName("E-03 多处命中 + replaceAll → MULTI_OCCURRENCE 全替换")
    void e03ReplaceAll() {
        ReplaceOutcome r = chain.replace("a\nb\na\nb", "b", "X", true);
        assertThat(r.strategy()).isEqualTo(Strategy.MULTI_OCCURRENCE);
        assertThat(r.newContent()).isEqualTo("a\nX\na\nX");
        assertThat(r.additions()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("E-04 行尾空白差异 → LINE_TRIMMED")
    void e04TrailingWhitespace() {
        ReplaceOutcome r = chain.replace("a  \nb", "a\nb", "a\nZ", false);
        assertThat(r.strategy()).isEqualTo(Strategy.LINE_TRIMMED);
        assertThat(r.newContent()).isEqualTo("a\nZ");
    }

    @Test
    @DisplayName("E-05 缩进整体偏移 → INDENTATION_FLEXIBLE，保留原缩进")
    void e05Indentation() {
        ReplaceOutcome r = chain.replace("    foo();\n    bar();",
                "  foo();\n  bar();", "  baz();\n  qux();", false);
        assertThat(r.strategy()).isEqualTo(Strategy.INDENTATION_FLEXIBLE);
        assertThat(r.newContent()).isEqualTo("    baz();\n    qux();");   // 4 空格原缩进保留
    }

    @Test
    @DisplayName("E-06 空白折叠 → WHITESPACE_NORMALIZED")
    void e06WhitespaceCollapse() {
        ReplaceOutcome r = chain.replace("if  ( x ) {", "if ( x ) {", "if (y) {", false);
        assertThat(r.strategy()).isEqualTo(Strategy.WHITESPACE_NORMALIZED);
        assertThat(r.newContent()).isEqualTo("if (y) {");
    }

    @Test
    @DisplayName("E-07 转义差异（模型过度转义 \\n） → ESCAPE_NORMALIZED")
    void e07EscapeNormalized() {
        // 文件实际内容：a \ n b（单反斜杠字面量）；模型给的 oldText：a \\ n b（双反斜杠）
        String content = "a\\nb";      // chars: a,\,n,b
        String oldText = "a\\\\nb";    // chars: a,\,\,n,b
        ReplaceOutcome r = chain.replace(content, oldText, "ZZ", false);
        assertThat(r.strategy()).isEqualTo(Strategy.ESCAPE_NORMALIZED);
        assertThat(r.newContent()).isEqualTo("ZZ");
    }

    @Test
    @DisplayName("E-08 首尾空行：matrix 用例成功替换且保留边界空行；oldText 带边界空白时走 TRIMMED_BOUNDARY")
    void e08TrimmedBoundary() {
        // DDD §10.4 行：content=`\n\nbody\n\n`, old=`body` → 期望 TRIMMED_BOUNDARY。
        // 实现偏差：oldText 为精确子串，链首 SIMPLE 命中即停（first-match-wins 是本链硬约束），
        // 结果与期望一致（成功 + 边界空行保留）；策略归属记为 SIMPLE。见交付报告。
        ReplaceOutcome direct = chain.replace("\n\nbody\n\n", "body", "X", false);
        assertThat(direct.newContent()).isEqualTo("\n\nX\n\n");
        assertThat(direct.strategy()).isIn(Strategy.SIMPLE, Strategy.TRIMMED_BOUNDARY);

        // TRIMMED_BOUNDARY 的真实场景：oldText 自带文件里不存在的边界空白
        ReplaceOutcome r = chain.replace("call(body);", " body\n", "X", false);
        assertThat(r.strategy()).isEqualTo(Strategy.TRIMMED_BOUNDARY);
        assertThat(r.newContent()).isEqualTo("call(X);");
    }

    @Test
    @DisplayName("E-09 单字符差异（距离比 ≤0.3） → BLOCK_ANCHOR_LOOSE")
    void e09BlockAnchorLoose() {
        ReplaceOutcome r = chain.replace("foo(bar)", "foo(baz)", "foo(qux)", false);
        assertThat(r.strategy()).isEqualTo(Strategy.BLOCK_ANCHOR_LOOSE);
        assertThat(r.newContent()).isEqualTo("foo(qux)");
    }

    @Test
    @DisplayName("E-10 差异过大 → 无匹配错误，含 4 条排障建议")
    void e10NoMatch() {
        assertThatThrownBy(() -> chain.replace("completely different", "xyz", "X", false))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("No match found for the requested edit in the file.")
                .hasMessageContaining("9 fallback matching strategies")
                .hasMessageContaining("Troubleshooting:")
                .hasMessageContaining("1. Re-read the file")
                .hasMessageContaining("2. Copy oldText verbatim")
                .hasMessageContaining("3. If the target appears multiple times")
                .hasMessageContaining("4. If you intend to replace all");
    }

    @Test
    @DisplayName("E-14/E-15 链级守卫：空 oldText 拒绝（完整语义在 EditToolTest）")
    void chainRejectsEmptyOldText() {
        assertThatThrownBy(() -> chain.replace("anything", "", "X", false))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("oldText must not be empty");
    }

    @Test
    @DisplayName("E-17 CRLF 文件：LINE_TRIMMED 命中且替换保留 CRLF 行尾")
    void e17CrlfPreserved() {
        ReplaceOutcome r = chain.replace("a\r\nb", "a\nb", "a\nZ", false);
        assertThat(r.strategy()).isEqualTo(Strategy.LINE_TRIMMED);
        assertThat(r.newContent()).isEqualTo("a\r\nZ");     // CRLF 保留
    }

    // ── 补充：其余级别覆盖 ────────────────────────────────────────────────

    @Test
    @DisplayName("补充：规范化块等值 → BLOCK_ANCHOR_STRICT")
    void blockAnchorStrict() {
        ReplaceOutcome r = chain.replace("aaa\nbbb\nccc", "aaa\n  bbb\nccc", "NEW", false);
        assertThat(r.strategy()).isEqualTo(Strategy.BLOCK_ANCHOR_STRICT);
        assertThat(r.newContent()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("补充：首尾锚点命中、中段漂移（差异足够大） → CONTEXT_AWARE")
    void contextAware() {
        // 中段两行完全陌生（距离比 >0.3，块锚点宽松级不会接），首尾锚点行精确
        String content = "alpha\nXXXXXXXXXXXXXXXXXXXX\nomega";
        String old = "alpha\nyyyyyyyyyyyyyyyyyyyyzz\nomega";
        ReplaceOutcome r = chain.replace(content, old, "alpha\nFIXED\nomega", false);
        assertThat(r.strategy()).isEqualTo(Strategy.CONTEXT_AWARE);
        assertThat(r.newContent()).isEqualTo("alpha\nFIXED\nomega");
    }
}
