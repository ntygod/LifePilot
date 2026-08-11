package com.lifepilot.skill.spec;

import com.lifepilot.skill.MarkdownSkillParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MarkdownSkillParser 对新 frontmatter 规范的解析行为测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
class MarkdownSkillParser_新规范测试 {

    private final MarkdownSkillParser parser = new MarkdownSkillParser();

    @Test
    void 解析必需字段() {
        var md = """
                ---
                name: github-workflow
                description: 当需要创建/合并/审查 PR 时使用。关键词 pr / issue / ci
                version: 1.0.0
                ---
                ## 触发判断
                - 创建 PR
                """;

        var parsed = parser.parse(md);

        assertThat(parsed.frontmatter().name()).isEqualTo("github-workflow");
        assertThat(parsed.frontmatter().version()).isEqualTo("1.0.0");
        assertThat(parsed.body()).contains("## 触发判断");
    }

    @Test
    void 解析metadata_zhiwei嵌套块() {
        var md = """
                ---
                name: x
                description: 当需要测试时使用
                version: 1.0.0
                metadata:
                  zhiwei:
                    suggested_tools: [a, b]
                    tags: [foo]
                    outputs: [text, file]
                    requires:
                      bins: [git]
                      env: [GITHUB_TOKEN]
                      os: [linux]
                      tools: [gh.pr.create]
                ---
                body
                """;

        var parsed = parser.parse(md);
        var meta = parsed.frontmatter().zhiweiMeta();

        assertThat(meta.suggestedTools()).containsExactly("a", "b");
        assertThat(meta.tags()).containsExactly("foo");
        assertThat(meta.outputs()).containsExactly("text", "file");
        assertThat(meta.requires().bins()).containsExactly("git");
        assertThat(meta.requires().env()).containsExactly("GITHUB_TOKEN");
        assertThat(meta.requires().os()).containsExactly("linux");
        assertThat(meta.requires().tools()).containsExactly("gh.pr.create");
    }

    @Test
    void 老字段id应被拒绝() {
        var md = """
                ---
                id: foo
                name: foo
                description: 当用时
                version: 1.0.0
                ---
                body
                """;

        assertThatThrownBy(() -> parser.parse(md))
                .hasMessageContaining("字段 'id' 已废弃")
                .hasMessageContaining("请用 'name'");
    }

    @Test
    void name不符合正则应拒绝() {
        var md = """
                ---
                name: Foo_Bar
                description: 当用时
                version: 1.0.0
                ---
                body
                """;

        assertThatThrownBy(() -> parser.parse(md))
                .hasMessageContaining("name 必须匹配正则");
    }

    @Test
    void 缺少必需字段应拒绝() {
        var md = "---\nname: x\n---\nbody";

        assertThatThrownBy(() -> parser.parse(md))
                .hasMessageContaining("缺少必需字段");
    }

    @Test
    void version不符合semver应拒绝() {
        var md = """
                ---
                name: x
                description: 当用时
                version: latest
                ---
                body
                """;

        assertThatThrownBy(() -> parser.parse(md))
                .hasMessageContaining("version")
                .hasMessageContaining("semver");
    }

    @Test
    void outputs包含未知值应拒绝() {
        var md = """
                ---
                name: bad-output
                description: 当需要测试输出形态时使用
                version: 1.0.0
                metadata:
                  zhiwei:
                    outputs: [hologram]
                ---
                body
                """;

        assertThatThrownBy(() -> parser.parse(md))
                .hasMessageContaining("outputs")
                .hasMessageContaining("hologram");
    }

}
