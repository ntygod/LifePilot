package com.lifepilot.skill.markdown;

import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.skill.spec.SkillZhiweiMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarkdownSkillSerializer} 单元测试。
 *
 * @author zsg
 * @since 2026-07-02
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
                .name("writing-assistant")
                .description("当用户要写文章、润色文案或生成报告草稿时使用。")
                .version("2.0.0")
                .source(new SkillSource.UserDefined("/skills/writing-assistant", null))
                .instructions("""
                        # 写作助手

                        ## 触发判断
                        - 写作
                        ## 决策路径
                        1. 判断受众和渠道
                        ## 输出标准
                        - text
                        ## 失败策略
                        - 缺少素材时追问
                        """)
                .suggestedTools(List.of("file.read", "file.write"))
                .metadata(Map.of())
                .zhiweiMeta(new SkillZhiweiMeta(
                        List.of("file.read", "file.write"),
                        List.of("writing", "draft"),
                        List.of("text", "file"),
                        SkillRequires.empty()))
                .build();
    }

    @Test
    void 标准定义_序列化包含v3Frontmatter和Body() {
        String result = serializer.serialize(sampleDefinition());

        assertThat(result).startsWith("---\n");
        assertThat(result).doesNotContain("id:");
        assertThat(result).doesNotContain("suggested-tools:");
        assertThat(result).contains("name: writing-assistant");
        assertThat(result).contains("description: 当用户要写文章、润色文案或生成报告草稿时使用。");
        assertThat(result).contains("version: 2.0.0");
        assertThat(result).contains("metadata:");
        assertThat(result).contains("zhiwei:");
        assertThat(result).contains("suggested_tools:");
        assertThat(result).contains("- file.read");
        assertThat(result).contains("outputs:");
        assertThat(result).contains("- text");
        assertThat(result).contains("## 触发判断");
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
    void 空metadata和空zhiweiMeta_不输出metadata节点() {
        var def = sampleDefinition().toBuilder()
                .suggestedTools(List.of())
                .metadata(Map.of())
                .zhiweiMeta(SkillZhiweiMeta.empty())
                .build();

        String result = serializer.serialize(def);

        assertThat(result).doesNotContain("metadata:");
    }

    @Test
    void 非空metadata_输出metadata扩展节点() {
        var def = sampleDefinition().toBuilder()
                .metadata(Map.of("custom-key", "custom-value"))
                .build();

        String result = serializer.serialize(def);

        assertThat(result).contains("metadata:");
        assertThat(result).contains("custom-key: custom-value");
    }

    @Test
    void serialize结果应能被当前Parser读回() {
        String result = serializer.serialize(sampleDefinition());
        var parsed = new MarkdownSkillParser().parse(result);

        assertThat(parsed.frontmatter().name()).isEqualTo("writing-assistant");
        assertThat(parsed.frontmatter().zhiweiMeta().suggestedTools())
                .containsExactly("file.read", "file.write");
        assertThat(parsed.frontmatter().zhiweiMeta().outputs())
                .containsExactly("text", "file");
        assertThat(parsed.body()).contains("## 失败策略");
    }
}
