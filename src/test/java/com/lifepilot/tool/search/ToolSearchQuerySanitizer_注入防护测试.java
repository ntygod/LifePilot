package com.lifepilot.tool.search;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSearchQuerySanitizer_注入防护测试 {

    private final ToolSearchQuerySanitizer sanitizer = new ToolSearchQuerySanitizer();

    @Test
    void 空字符串_返回空() {
        assertThat(sanitizer.sanitize(null)).isEqualTo("");
        assertThat(sanitizer.sanitize("")).isEqualTo("");
        assertThat(sanitizer.sanitize("   ")).isEqualTo("");
    }

    @Test
    void 普通关键词_每个token加引号() {
        assertThat(sanitizer.sanitize("delete file"))
                .isEqualTo("\"delete\" \"file\"");
    }

    @Test
    void FTS5保留字符被清除后依然有效() {
        assertThat(sanitizer.sanitize("delete * file"))
                .isEqualTo("\"delete\" \"file\"");
        assertThat(sanitizer.sanitize("delete (file)"))
                .isEqualTo("\"delete\" \"file\"");
    }

    @Test
    void 单词里有双引号_引号被剥离() {
        assertThat(sanitizer.sanitize("de\"lete file"))
                .doesNotContain("de\"lete")
                .contains("\"de\"", "\"lete\"", "\"file\"");
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
        assertThat(output).doesNotMatch("(^|\\s)(AND|OR|NOT|NEAR)(\\s|$)");
    }
}
