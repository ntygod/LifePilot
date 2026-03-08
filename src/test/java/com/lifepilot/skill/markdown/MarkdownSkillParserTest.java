package com.lifepilot.skill.markdown;

import com.lifepilot.skill.markdown.MarkdownSkillParser.ParseResult;
import com.lifepilot.skill.model.SkillDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarkdownSkillParser} 单元测试。
 *
 * @author zsg
 * @since 2026-07-28
 */
class MarkdownSkillParserTest {

    private MarkdownSkillParser parser;

    @BeforeEach
    void setUp() {
        parser = new MarkdownSkillParser();
    }

    // ─────────────────────────────────────────────
    //  标准解析
    // ─────────────────────────────────────────────

    private static final String STANDARD_SKILL_MD = """
            ---
            id: writing-assistant
            name: 写作助手
            description: 帮助用户撰写高质量文章
            version: 2.0.0
            suggested-tools:
              - builtin.todo.create
              - builtin.memory.search
            ---
            
            你是一个专业的写作助手，帮助用户撰写高质量文章。
            
            ## 写作流程
            1. 理解用户需求
            2. 构建大纲
            3. 撰写初稿
            """;

    @Test
    void 标准SKILL_MD_解析成功() {
        ParseResult result = parser.parse(STANDARD_SKILL_MD);

        assertThat(result.success()).isTrue();
        assertThat(result.errors()).isEmpty();

        SkillDefinition def = result.definition();
        assertThat(def).isNotNull();
        assertThat(def.id()).isEqualTo("writing-assistant");
        assertThat(def.name()).isEqualTo("写作助手");
        assertThat(def.description()).isEqualTo("帮助用户撰写高质量文章");
        assertThat(def.version()).isEqualTo("2.0.0");
        assertThat(def.suggestedTools()).containsExactly("builtin.todo.create", "builtin.memory.search");
        assertThat(def.instructions()).contains("专业的写作助手");
        assertThat(def.instructions()).contains("## 写作流程");
    }

    @Test
    void 无suggestedTools_解析为空列表() {
        String content = """
                ---
                id: simple-skill
                name: 简单技能
                description: 一个简单的技能
                ---
                
                这是指令内容。
                """;

        ParseResult result = parser.parse(content);
        assertThat(result.success()).isTrue();
        assertThat(result.definition().suggestedTools()).isEmpty();
    }

    @Test
    void 默认版本号_为1_0_0() {
        String content = """
                ---
                id: no-version
                name: 无版本
                description: 没有指定版本号
                ---
                
                指令内容。
                """;

        ParseResult result = parser.parse(content);
        assertThat(result.success()).isTrue();
        assertThat(result.definition().version()).isEqualTo("1.0.0");
    }

    // ─────────────────────────────────────────────
    //  metadata 映射（tags/category/author/dependencies）
    // ─────────────────────────────────────────────

    @Test
    void 顶层tags_映射到metadata() {
        String content = """
                ---
                id: tagged-skill
                name: 带标签技能
                description: 测试标签映射
                tags:
                  - writing
                  - productivity
                ---
                
                指令内容。
                """;

        ParseResult result = parser.parse(content);
        assertThat(result.success()).isTrue();
        assertThat(result.definition().metadata()).containsEntry("tags", "writing,productivity");
    }

    @Test
    void 顶层category_映射到metadata() {
        String content = """
                ---
                id: categorized-skill
                name: 分类技能
                description: 测试分类映射
                category: productivity
                ---
                
                指令内容。
                """;

        ParseResult result = parser.parse(content);
        assertThat(result.success()).isTrue();
        assertThat(result.definition().metadata()).containsEntry("category", "productivity");
    }

    @Test
    void 顶层author_映射到metadata() {
        String content = """
                ---
                id: authored-skill
                name: 有作者技能
                description: 测试作者映射
                author: zsg
                ---
                
                指令内容。
                """;

        ParseResult result = parser.parse(content);
        assertThat(result.success()).isTrue();
        assertThat(result.definition().metadata()).containsEntry("author", "zsg");
    }

    @Test
    void 顶层dependencies_映射到metadata() {
        String content = """
                ---
                id: dep-skill
                name: 有依赖技能
                description: 测试依赖映射
                dependencies:
                  - writing-assistant
                  - memory-skill
                ---
                
                指令内容。
                """;

        ParseResult result = parser.parse(content);
        assertThat(result.success()).isTrue();
        assertThat(result.definition().metadata()).containsEntry("dependencies", "writing-assistant,memory-skill");
    }

    @Test
    void metadata节点与顶层字段_合并到metadata() {
        String content = """
                ---
                id: merged-skill
                name: 合并元数据
                description: 测试 metadata 合并
                category: writing
                metadata:
                  custom-key: custom-value
                ---
                
                指令内容。
                """;

        ParseResult result = parser.parse(content);
        assertThat(result.success()).isTrue();
        Map<String, String> meta = result.definition().metadata();
        assertThat(meta).containsEntry("category", "writing");
        assertThat(meta).containsEntry("custom-key", "custom-value");
    }

    // ─────────────────────────────────────────────
    //  错误场景
    // ─────────────────────────────────────────────

    @Test
    void 空内容_返回失败() {
        ParseResult result = parser.parse("");
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).contains("SKILL.md 内容不能为空");
    }

    @Test
    void null内容_返回失败() {
        ParseResult result = parser.parse(null);
        assertThat(result.success()).isFalse();
    }

    @Test
    void 缺少Frontmatter分隔符_返回失败() {
        ParseResult result = parser.parse("没有分隔符的内容");
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("缺少 YAML Frontmatter 分隔符"));
    }

    @Test
    void 缺少必填字段id_返回失败() {
        String content = """
                ---
                name: 无ID技能
                description: 缺少ID
                ---
                
                指令内容。
                """;

        ParseResult result = parser.parse(content);
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("缺少必填字段: id"));
    }

    @Test
    void MarkdownBody为空_返回instructions不能为空() {
        String content = """
                ---
                id: empty-body
                name: 空Body
                description: Body为空
                ---
                """;

        ParseResult result = parser.parse(content);
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).contains("instructions 不能为空");
    }
}
