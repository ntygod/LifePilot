package com.lifepilot.tool.search;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanitizer 注入防护与基本格式测试 —— 适配 trigram tokenizer 切分策略。
 */
class ToolSearchQuerySanitizer_注入防护测试 {

    private final ToolSearchQuerySanitizer sanitizer = new ToolSearchQuerySanitizer();

    @Test
    void 空字符串_返回空() {
        assertThat(sanitizer.sanitize(null)).isEqualTo("");
        assertThat(sanitizer.sanitize("")).isEqualTo("");
        assertThat(sanitizer.sanitize("   ")).isEqualTo("");
    }

    @Test
    void 普通英文关键词_切trigram用OR连接() {
        // "delete" → "del" "ele" "let" "ete"; "file" → "fil" "ile"
        String out = sanitizer.sanitize("delete file");
        assertThat(out)
                .contains("\"del\"")
                .contains("\"ele\"")
                .contains("\"fil\"")
                .contains("\"ile\"")
                .contains(" OR ");
    }

    @Test
    void FTS5保留字符被清除() {
        String out = sanitizer.sanitize("delete * file");
        assertThat(out).contains("\"del\"").contains("\"fil\"");
        out = sanitizer.sanitize("delete (file)");
        assertThat(out).contains("\"del\"").contains("\"fil\"");
    }

    @Test
    void 双引号被剥离() {
        String out = sanitizer.sanitize("de\"lete file");
        assertThat(out).doesNotContain("de\"lete\"");
        // 双引号被替换为空格 → "de" 与 "lete" 分离 → "de" 走 2 字 phrase，"lete" 切 trigram
        assertThat(out).contains("\" de\"").contains("\"let\"");
    }

    @Test
    void 中文query_切3字符滑窗phrase() {
        // "删除文件" 4 字 → trigram phrase: "删除文" + "除文件"
        String out = sanitizer.sanitize("删除文件");
        assertThat(out).contains("\"删除文\"").contains("\"除文件\"").contains(" OR ");
    }

    @Test
    void 两字短token_用空格前缀凑3字符() {
        // "git" 3 字 → 一个 trigram phrase
        // "ab" 2 字 → 用空格前缀 "\" ab\""
        String out = sanitizer.sanitize("ab cd");
        assertThat(out).contains("\" ab\"").contains("\" cd\"");
    }

    @Property
    void 任何输入_输出不包含未闭合的引号(@ForAll @StringLength(max = 200) String input) {
        String output = sanitizer.sanitize(input);
        long quoteCount = output.chars().filter(c -> c == '"').count();
        assertThat(quoteCount % 2).isZero();
    }

    @Property
    void 任何输入_输出不含裸露的FTS5关键字(@ForAll @StringLength(max = 200) String input) {
        String output = sanitizer.sanitize(input);
        // sanitizer 自身会输出 OR 作为 phrase 连接符（带空格），仅校验"裸露 token 形式"未泄漏：
        // 即不存在被双引号闭合后紧邻保留字的形态 \"x\" AND/NOT/NEAR \"y\"
        // OR 是正常输出（OR 连接 phrase），不算泄漏
        var stray = java.util.regex.Pattern.compile("\"\\s+(AND|NOT|NEAR)\\s+\"").matcher(output);
        assertThat(stray.find()).as("output=%s", output).isFalse();
    }
}
