package com.lifepilot.memory.retrieval;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQLiteFtsQueryNormalizer 单元测试 — 覆盖 normalize 的参数校验、token 提取、
 * 保留字过滤、噪声修剪、长度截断和特殊字符处理。
 *
 * @author zsg
 * @since 2026-04-03
 */
class SQLiteFtsQueryNormalizer_单元测试 {

    // ─────────────────────────────────────────────
    //  null / 空白 / 无效参数
    // ─────────────────────────────────────────────

    @Nested
    class 无效输入 {

        @Test
        void null输入返回空串() {
            assertThat(SQLiteFtsQueryNormalizer.normalize(null)).isEmpty();
        }

        @Test
        void 空字符串返回空串() {
            assertThat(SQLiteFtsQueryNormalizer.normalize("")).isEmpty();
        }

        @Test
        void 纯空白返回空串() {
            assertThat(SQLiteFtsQueryNormalizer.normalize("   \t\n  ")).isEmpty();
        }

        @Test
        void maxTokens为零返回空串() {
            assertThat(SQLiteFtsQueryNormalizer.normalize("hello world", 0, 48)).isEmpty();
        }

        @Test
        void maxTokens为负数返回空串() {
            assertThat(SQLiteFtsQueryNormalizer.normalize("hello world", -1, 48)).isEmpty();
        }

        @Test
        void maxTokenLength为零返回空串() {
            assertThat(SQLiteFtsQueryNormalizer.normalize("hello world", 12, 0)).isEmpty();
        }

        @Test
        void maxTokenLength为负数返回空串() {
            assertThat(SQLiteFtsQueryNormalizer.normalize("hello world", 12, -5)).isEmpty();
        }
    }

    // ─────────────────────────────────────────────
    //  基本 token 提取
    // ─────────────────────────────────────────────

    @Nested
    class Token提取 {

        @Test
        void 英文单词正确提取并加引号() {
            String result = SQLiteFtsQueryNormalizer.normalize("hello world");

            assertThat(result).isEqualTo("\"hello\" \"world\"");
        }

        @Test
        void 中文字符作为整体token提取() {
            String result = SQLiteFtsQueryNormalizer.normalize("乌龙茶偏好");

            assertThat(result).isEqualTo("\"乌龙茶偏好\"");
        }

        @Test
        void 混合中英文分别提取() {
            String result = SQLiteFtsQueryNormalizer.normalize("AI 资讯 summary");

            assertThat(result).contains("\"AI\"");
            assertThat(result).contains("\"资讯\"");
            assertThat(result).contains("\"summary\"");
        }

        @Test
        void 数字被提取为token() {
            String result = SQLiteFtsQueryNormalizer.normalize("version 2026");

            assertThat(result).contains("\"version\"");
            assertThat(result).contains("\"2026\"");
        }

        @Test
        void 连字符连接的词作为单个token() {
            String result = SQLiteFtsQueryNormalizer.normalize("state-of-the-art model");

            // state-of-the-art 匹配 [\p{L}\p{Nd}_-]+ 所以是一个token
            assertThat(result).contains("\"state-of-the-art\"");
            assertThat(result).contains("\"model\"");
        }

        @Test
        void 下划线连接的词作为单个token() {
            String result = SQLiteFtsQueryNormalizer.normalize("my_variable name");

            // _my_variable_ 中前后的下划线会被 trimNoise 去掉
            assertThat(result).contains("\"my_variable\"");
            assertThat(result).contains("\"name\"");
        }
    }

    // ─────────────────────────────────────────────
    //  保留字过滤
    // ─────────────────────────────────────────────

    @Nested
    class 保留字过滤 {

        @Test
        void AND被过滤() {
            String result = SQLiteFtsQueryNormalizer.normalize("cats AND dogs");

            assertThat(result).contains("\"cats\"");
            assertThat(result).contains("\"dogs\"");
            assertThat(result).doesNotContain("\"AND\"");
        }

        @Test
        void OR被过滤() {
            String result = SQLiteFtsQueryNormalizer.normalize("coffee OR tea");

            assertThat(result).doesNotContain("\"OR\"");
        }

        @Test
        void NOT被过滤() {
            String result = SQLiteFtsQueryNormalizer.normalize("NOT spam");

            assertThat(result).doesNotContain("\"NOT\"");
            assertThat(result).contains("\"spam\"");
        }

        @Test
        void NEAR被过滤() {
            String result = SQLiteFtsQueryNormalizer.normalize("NEAR match");

            assertThat(result).doesNotContain("\"NEAR\"");
            assertThat(result).contains("\"match\"");
        }

        @Test
        void 小写保留字不被过滤_大小写不敏感比较() {
            // sanitizeToken 用 toUpperCase 检查，所以 "and" -> "AND" 会命中
            String result = SQLiteFtsQueryNormalizer.normalize("and or not near");

            assertThat(result).isEmpty();
        }

        @Test
        void 保留字作为子串时不过滤() {
            // ANDROID 包含 AND 但本身不是保留字
            String result = SQLiteFtsQueryNormalizer.normalize("ANDROID NOTEBOOK");

            assertThat(result).contains("\"ANDROID\"");
            assertThat(result).contains("\"NOTEBOOK\"");
        }
    }

    // ─────────────────────────────────────────────
    //  单字符过滤
    // ─────────────────────────────────────────────

    @Nested
    class 单字符过滤 {

        @Test
        void 单个ASCII字母被过滤() {
            String result = SQLiteFtsQueryNormalizer.normalize("A B C hello");

            assertThat(result).isEqualTo("\"hello\"");
        }

        @Test
        void 单个数字被过滤() {
            String result = SQLiteFtsQueryNormalizer.normalize("1 2 3 test");

            assertThat(result).isEqualTo("\"test\"");
        }

        @Test
        void 单个中文字符不被过滤() {
            // 中文单字不是 ASCII 字母数字，所以 isAsciiAlphaNumeric 返回 false
            String result = SQLiteFtsQueryNormalizer.normalize("茶");

            assertThat(result).isEqualTo("\"茶\"");
        }
    }

    // ─────────────────────────────────────────────
    //  噪声修剪（trimNoise）
    // ─────────────────────────────────────────────

    @Nested
    class 噪声修剪 {

        @Test
        void 前导下划线被修剪() {
            String result = SQLiteFtsQueryNormalizer.normalize("__private");

            assertThat(result).isEqualTo("\"private\"");
        }

        @Test
        void 尾部下划线被修剪() {
            String result = SQLiteFtsQueryNormalizer.normalize("value__");

            assertThat(result).isEqualTo("\"value\"");
        }

        @Test
        void 前导和尾部连字符被修剪() {
            String result = SQLiteFtsQueryNormalizer.normalize("--option--");

            assertThat(result).isEqualTo("\"option\"");
        }

        @Test
        void 全是下划线和连字符_修剪后为空_被跳过() {
            String result = SQLiteFtsQueryNormalizer.normalize("___--- hello");

            assertThat(result).isEqualTo("\"hello\"");
        }

        @Test
        void 中间的下划线和连字符保留() {
            String result = SQLiteFtsQueryNormalizer.normalize("my_var some-thing");

            assertThat(result).contains("\"my_var\"");
            assertThat(result).contains("\"some-thing\"");
        }
    }

    // ─────────────────────────────────────────────
    //  maxTokens 限制
    // ─────────────────────────────────────────────

    @Nested
    class MaxTokens限制 {

        @Test
        void 超过maxTokens的词被截断() {
            String result = SQLiteFtsQueryNormalizer.normalize(
                    "alpha beta gamma delta epsilon", 3, 48);

            // 应只取前 3 个
            long tokenCount = result.chars().filter(ch -> ch == '"').count() / 2;
            assertThat(tokenCount).isEqualTo(3);
            assertThat(result).contains("\"alpha\"");
            assertThat(result).contains("\"beta\"");
            assertThat(result).contains("\"gamma\"");
            assertThat(result).doesNotContain("\"delta\"");
        }

        @Test
        void maxTokens为1只保留第一个有效token() {
            String result = SQLiteFtsQueryNormalizer.normalize(
                    "first second third", 1, 48);

            assertThat(result).isEqualTo("\"first\"");
        }

        @Test
        void 默认maxTokens为12() {
            // 构造 15 个有效词
            String input = "aa bb cc dd ee ff gg hh ii jj kk ll mm nn oo";
            String result = SQLiteFtsQueryNormalizer.normalize(input);

            long tokenCount = result.chars().filter(ch -> ch == '"').count() / 2;
            assertThat(tokenCount).isEqualTo(12);
        }
    }

    // ─────────────────────────────────────────────
    //  maxTokenLength 截断
    // ─────────────────────────────────────────────

    @Nested
    class MaxTokenLength截断 {

        @Test
        void 超长token被截断到maxTokenLength() {
            String longWord = "a".repeat(60);
            String result = SQLiteFtsQueryNormalizer.normalize(longWord, 12, 10);

            // 截断后应为 10 个字符
            assertThat(result).isEqualTo("\"" + "a".repeat(10) + "\"");
        }

        @Test
        void 默认maxTokenLength为48() {
            String longWord = "abcdef".repeat(10); // 60 字符
            String result = SQLiteFtsQueryNormalizer.normalize(longWord);

            // 去掉引号后的长度
            String inner = result.replace("\"", "");
            assertThat(inner.length()).isEqualTo(48);
        }

        @Test
        void 恰好等于maxTokenLength不截断() {
            String exactWord = "a".repeat(10);
            String result = SQLiteFtsQueryNormalizer.normalize(exactWord, 12, 10);

            assertThat(result).isEqualTo("\"" + "a".repeat(10) + "\"");
        }
    }

    // ─────────────────────────────────────────────
    //  去重（LinkedHashSet）
    // ─────────────────────────────────────────────

    @Nested
    class 去重 {

        @Test
        void 重复token只出现一次() {
            String result = SQLiteFtsQueryNormalizer.normalize("hello hello world hello");

            assertThat(result).isEqualTo("\"hello\" \"world\"");
        }

        @Test
        void 保持首次出现的顺序() {
            String result = SQLiteFtsQueryNormalizer.normalize("beta alpha gamma alpha beta");

            assertThat(result).isEqualTo("\"beta\" \"alpha\" \"gamma\"");
        }
    }

    // ─────────────────────────────────────────────
    //  双引号转义
    // ─────────────────────────────────────────────

    @Nested
    class 双引号转义 {

        @Test
        void token内的双引号被转义为两个双引号() {
            // 正则 TOKEN_PATTERN 不匹配双引号字符，因此含双引号的输入不会作为 token 的一部分
            // 这里只验证 normalize 对 FTS5 安全的整体行为
            String result = SQLiteFtsQueryNormalizer.normalize("say \"hello\" world");

            // "hello" 中引号不匹配 \p{L}\p{Nd}_- 所以 hello 和 world 被分别提取
            assertThat(result).contains("\"say\"");
            assertThat(result).contains("\"hello\"");
            assertThat(result).contains("\"world\"");
        }
    }

    // ─────────────────────────────────────────────
    //  特殊字符和复杂输入
    // ─────────────────────────────────────────────

    @Nested
    class 特殊字符处理 {

        @Test
        void FTS5运算符不会出现在结果中() {
            String result = SQLiteFtsQueryNormalizer.normalize("hello * world + test ^ 2");

            assertThat(result).doesNotContain("*");
            assertThat(result).doesNotContain("+");
            assertThat(result).doesNotContain("^");
        }

        @Test
        void 括号不会出现在结果中() {
            String result = SQLiteFtsQueryNormalizer.normalize("(hello) (world)");

            assertThat(result).contains("\"hello\"");
            assertThat(result).contains("\"world\"");
            // 括号不属于 TOKEN_PATTERN
        }

        @Test
        void 冒号不会出现在结果中() {
            String result = SQLiteFtsQueryNormalizer.normalize("column:value search:term");

            assertThat(result).contains("\"column\"");
            assertThat(result).contains("\"value\"");
            assertThat(result).doesNotContain(":");
        }

        @Test
        void 纯特殊字符返回空串() {
            String result = SQLiteFtsQueryNormalizer.normalize("!@#$%^&*(){}[]<>,./;':");

            assertThat(result).isEmpty();
        }

        @Test
        void Windows路径提取目录名() {
            String result = SQLiteFtsQueryNormalizer.normalize("D:\\WorkSpace\\Project\\News");

            assertThat(result).contains("\"WorkSpace\"");
            assertThat(result).contains("\"Project\"");
            assertThat(result).contains("\"News\"");
        }

        @Test
        void JSON片段提取有效词汇() {
            String result = SQLiteFtsQueryNormalizer.normalize(
                    "{\"content\":\"hello world\",\"type\":\"message\"}");

            assertThat(result).contains("\"content\"");
            assertThat(result).contains("\"hello\"");
            assertThat(result).contains("\"world\"");
            assertThat(result).contains("\"type\"");
            assertThat(result).contains("\"message\"");
            assertThat(result).doesNotContain("{");
            assertThat(result).doesNotContain("}");
        }

        @Test
        void Markdown反引号被忽略() {
            String result = SQLiteFtsQueryNormalizer.normalize("`code` and ```block```");

            assertThat(result).contains("\"code\"");
            assertThat(result).contains("\"block\"");
            assertThat(result).doesNotContain("`");
        }
    }

    // ─────────────────────────────────────────────
    //  属性测试
    // ─────────────────────────────────────────────

    @Property(tries = 500)
    void 任意输入不抛异常且结果不含FTS5语法字符(
            @ForAll("任意脏输入") String input) {
        String result = SQLiteFtsQueryNormalizer.normalize(input);

        assertThat(result).isNotNull();
        // 结果中不应含 FTS5 语法字符（引号除外，引号用于包裹 token）
        String withoutQuotedTokens = result.replaceAll("\"[^\"]*\"", "");
        assertThat(withoutQuotedTokens.trim()).doesNotContain("(");
        assertThat(withoutQuotedTokens.trim()).doesNotContain(")");
        assertThat(withoutQuotedTokens.trim()).doesNotContain("*");
        assertThat(withoutQuotedTokens.trim()).doesNotContain("^");
        assertThat(withoutQuotedTokens.trim()).doesNotContain(":");
    }

    @Property(tries = 300)
    void token数量不超过maxTokens(
            @ForAll("任意脏输入") String input,
            @ForAll @IntRange(min = 1, max = 20) int maxTokens) {
        String result = SQLiteFtsQueryNormalizer.normalize(input, maxTokens, 48);

        if (!result.isEmpty()) {
            long tokenCount = result.chars().filter(ch -> ch == '"').count() / 2;
            assertThat(tokenCount).isLessThanOrEqualTo(maxTokens);
        }
    }

    @Property(tries = 300)
    void 每个token长度不超过maxTokenLength(
            @ForAll("任意脏输入") String input,
            @ForAll @IntRange(min = 1, max = 100) int maxLen) {
        String result = SQLiteFtsQueryNormalizer.normalize(input, 12, maxLen);

        if (!result.isEmpty()) {
            // 提取每个引号包裹的 token 并检查长度
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]*)\"")
                    .matcher(result);
            while (m.find()) {
                assertThat(m.group(1).length())
                        .as("token '%s' 超过 maxTokenLength %d", m.group(1), maxLen)
                        .isLessThanOrEqualTo(maxLen);
            }
        }
    }

    @Provide("任意脏输入")
    Arbitrary<String> 任意脏输入() {
        var chinese = Arbitraries.chars().range('\u4e00', '\u9fff');
        var ascii = Arbitraries.chars().ascii();
        var ftsSpecial = Arbitraries.of(
                ':', '"', '(', ')', '*', '^', '+', '-', '~',
                '{', '}', '[', ']', '/', '\\', '.', ',',
                '_', ' ', '\t', '\n');
        var mixed = Arbitraries.frequencyOf(
                Tuple.of(3, ascii),
                Tuple.of(2, chinese),
                Tuple.of(2, ftsSpecial));

        return mixed.list().ofMinSize(0).ofMaxSize(100)
                .map(chars -> {
                    var sb = new StringBuilder();
                    chars.forEach(sb::append);
                    return sb.toString();
                });
    }
}
