package com.lifepilot.memory.retrieval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQLite FTS5 查询正规化测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
class SQLiteFtsQueryNormalizerTest {

    @Test
    void normalize_应移除MarkdownJson和路径噪声() {
        String raw = """
                file.write {"content":"# AI 资讯汇总\\n`D:\\\\WorkSpace\\\\Project\\\\News`","path":"D:\\\\WorkSpace\\\\Project\\\\News\\\\AI_News_2026-03-24.md"}
                """;

        String normalized = SQLiteFtsQueryNormalizer.normalize(raw);

        assertThat(normalized)
                .contains("\"file\"")
                .contains("\"write\"")
                .contains("\"WorkSpace\"")
                .contains("\"Project\"")
                .contains("\"News\"")
                .doesNotContain("`")
                .doesNotContain("{")
                .doesNotContain("}");
    }

    @Test
    void normalize_应过滤保留字和单字符噪声() {
        String normalized = SQLiteFtsQueryNormalizer.normalize("AND OR NOT D code.execute python");

        assertThat(normalized)
                .contains("\"code\"")
                .contains("\"execute\"")
                .contains("\"python\"")
                .doesNotContain("\"AND\"")
                .doesNotContain("\"D\"");
    }
}
