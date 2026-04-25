package com.lifepilot.skill.markdown;

import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarkdownSkillSerializer} 单元测试。
 *
 * @author zsg
 * @since 2026-07-28
 */
class MarkdownSkillSerializerTest {

    private MarkdownSkillSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new MarkdownSkillSerializer();
    }

    private SkillDefinition sampleDefinition() {
        return SkillDefinition.builder()
                .id("writing-assistant")
                .name("写作助手")
                .description("帮助用户撰写高质量文章")
                .version("2.0.0")
                .source(new SkillSource.UserDefined("/skills/writing-assistant"))
                .instructions("你是一个专业的写作助手。")
                .suggestedTools(List.of("todo.create", "memory.search"))
                .metadata(Map.of())
                .build();
    }

    // ─────────────────────────────────────────────
    //  基本序列化
    // ─────────────────────────────────────────────

    @Test
    void 标准定义_序列化包含Frontmatter和Body() {
        String result = serializer.serialize(sampleDefinition());

        assertThat(result).startsWith("---\n");
        assertThat(result).contains("id: writing-assistant");
        assertThat(result).contains("name: 写作助手");
        assertThat(result).contains("description: 帮助用户撰写高质量文章");
        assertThat(result).contains("version: 2.0.0");
        assertThat(result).contains("suggested-tools:");
        assertThat(result).contains("- todo.create");
        assertThat(result).contains("- memory.search");
        assertThat(result).contains("你是一个专业的写作助手。");
    }

    @Test
    void 不包含allowedTools关键字() {
        String result = serializer.serialize(sampleDefinition());
        assertThat(result).doesNotContain("allowed-tools");
    }

    @Test
    void 不包含execution_memoryAccess_budget() {
        String result = serializer.serialize(sampleDefinition());
        assertThat(result).doesNotContain("execution:");
        assertThat(result).doesNotContain("memory-access:");
        assertThat(result).doesNotContain("budget:");
    }

    @Test
    void 空metadata_不输出metadata节点() {
        String result = serializer.serialize(sampleDefinition());
        assertThat(result).doesNotContain("metadata:");
    }

    @Test
    void 非空metadata_输出metadata节点() {
        var def = sampleDefinition().toBuilder()
                .metadata(Map.of("custom-key", "custom-value"))
                .build();
        String result = serializer.serialize(def);
        assertThat(result).contains("metadata:");
        assertThat(result).contains("custom-key: custom-value");
    }

    @Test
    void 空suggestedTools_输出空列表() {
        var def = sampleDefinition().toBuilder()
                .suggestedTools(List.of())
                .build();
        String result = serializer.serialize(def);
        assertThat(result).contains("suggested-tools");
    }

    // ─────────────────────────────────────────────
    //  Round Trip（序列化→解析）
    // ─────────────────────────────────────────────
    // TODO Phase B.6: 旧 Round Trip 测试依赖 com.lifepilot.skill.markdown.MarkdownSkillParser，
    // 该类已随 v2 规范重写删除，Round Trip 待 Phase B.6 接入新 parser 后重写。
}
