package com.lifepilot.memory.store.episodic;

import com.lifepilot.memory.store.episodic.EpisodicMemory;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FTS5 查询转义安全性属性测试 — 验证 {@code escapeFts5Query()} 对任意包含 FTS5 特殊字符的输入不抛异常。
 *
 * <p><b>Validates: Property 6, Requirements 2.7</b></p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class Fts5EscapePropertyTest {

;

    /** FTS5 关键字池。 */
    private static final String[] FTS5_KEYWORDS = {
            "AND", "OR", "NOT", "NEAR"
    };

    // ─────────────────────────────────────────────
    //  Property 6 — FTS5 查询转义安全性
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 2.7</b>
     *
     * <p>对任意包含 FTS5 特殊字符的查询字符串，{@code escapeFts5Query()} 转义后不抛异常，
     * 且返回值以双引号包裹。</p>
     */
    @Property(tries = 200)
    void FTS5特殊字符转义不抛异常(@ForAll("fts5SpecialStrings") String query) {
        String escaped = assertDoesNotThrow(
                () -> EpisodicMemory.escapeFts5Query(query),
                "escapeFts5Query() 对输入 [" + query + "] 抛出异常");

        // 转义结果必须以双引号包裹
        assertTrue(escaped.startsWith("\""), "转义结果应以双引号开头: " + escaped);
        assertTrue(escaped.endsWith("\""), "转义结果应以双引号结尾: " + escaped);
    }

    /**
     * <b>Validates: Requirements 2.7</b>
     *
     * <p>对任意包含中文和 FTS5 特殊字符混合的查询字符串，{@code escapeFts5Query()} 转义后不抛异常。</p>
     */
    @Property(tries = 200)
    void 中文混合特殊字符转义不抛异常(@ForAll("chineseMixedStrings") String query) {
        String escaped = assertDoesNotThrow(
                () -> EpisodicMemory.escapeFts5Query(query),
                "escapeFts5Query() 对中文混合输入 [" + query + "] 抛出异常");

        assertTrue(escaped.startsWith("\"") && escaped.endsWith("\""),
                "转义结果应以双引号包裹: " + escaped);
    }

    /**
     * <b>Validates: Requirements 2.7</b>
     *
     * <p>对任意包含双引号的查询字符串，{@code escapeFts5Query()} 应将内部双引号转义为两个双引号。</p>
     */
    @Property(tries = 100)
    void 内部双引号正确转义(@ForAll("stringsWithQuotes") String query) {
        String escaped = EpisodicMemory.escapeFts5Query(query);

        // 去掉首尾包裹的双引号后，内部不应有单独的双引号（都应成对出现）
        String inner = escaped.substring(1, escaped.length() - 1);
        // 将所有 "" 替换为空后，不应再有残留的 "
        String afterReplace = inner.replace("\"\"", "");
        assertFalse(afterReplace.contains("\""),
                "转义后内部仍有未转义的双引号: input=[" + query + "], escaped=[" + escaped + "]");
    }

    /**
     * <b>Validates: Requirements 2.7</b>
     *
     * <p>对任意字符串（含 FTS5 关键字 AND/OR/NOT/NEAR），{@code escapeFts5Query()} 转义后不抛异常。</p>
     */
    @Property(tries = 100)
    void FTS5关键字转义不抛异常(@ForAll("stringsWithKeywords") String query) {
        assertDoesNotThrow(
                () -> EpisodicMemory.escapeFts5Query(query),
                "escapeFts5Query() 对含关键字输入 [" + query + "] 抛出异常");
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /** 生成包含随机 FTS5 特殊字符的字符串。 */
    @Provide
    Arbitrary<String> fts5SpecialStrings() {
        // 混合普通字符和 FTS5 特殊字符
        var normalChars = Arbitraries.chars().alpha();
        var specialChars = Arbitraries.of(
                ':', '"', '(', ')', '*', '^', '+', '-', '~');
        var mixedChars = Arbitraries.oneOf(normalChars, specialChars);

        return mixedChars.list().ofMinSize(1).ofMaxSize(50)
                .map(chars -> {
                    var sb = new StringBuilder();
                    chars.forEach(sb::append);
                    return sb.toString();
                });
    }

    /** 生成中文与 FTS5 特殊字符混合的字符串。 */
    @Provide
    Arbitrary<String> chineseMixedStrings() {
        // 中文字符范围 \u4e00-\u9fff
        var chineseChars = Arbitraries.chars().range('\u4e00', '\u9fff');
        var specialChars = Arbitraries.of(
                ':', '"', '(', ')', '*', '^', '+', '-', '~');
        var asciiChars = Arbitraries.chars().alpha();
        var mixedChars = Arbitraries.frequencyOf(
                Tuple.of(3, chineseChars),
                Tuple.of(2, specialChars),
                Tuple.of(1, asciiChars));

        return mixedChars.list().ofMinSize(1).ofMaxSize(30)
                .map(chars -> {
                    var sb = new StringBuilder();
                    chars.forEach(sb::append);
                    return sb.toString();
                });
    }

    /** 生成包含双引号的字符串。 */
    @Provide
    Arbitrary<String> stringsWithQuotes() {
        var normalChars = Arbitraries.chars().alpha();
        var quoteChar = Arbitraries.just('"');
        var mixedChars = Arbitraries.frequencyOf(
                Tuple.of(3, normalChars),
                Tuple.of(2, quoteChar));

        return mixedChars.list().ofMinSize(1).ofMaxSize(30)
                .map(chars -> {
                    var sb = new StringBuilder();
                    chars.forEach(sb::append);
                    return sb.toString();
                });
    }

    /** 生成包含 FTS5 关键字的字符串。 */
    @Provide
    Arbitrary<String> stringsWithKeywords() {
        var words = Arbitraries.oneOf(
                Arbitraries.of(FTS5_KEYWORDS),
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8));

        return words.list().ofMinSize(1).ofMaxSize(6)
                .map(parts -> String.join(" ", parts));
    }
}
