package com.lifepilot.skill.generation;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.markdown.MarkdownSkillSerializer;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.validation.SkillValidationPipeline;
import com.lifepilot.skill.validation.SkillValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SkillGenerator 单元测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class SkillGeneratorTest {

    private GenerationRouter generationRouter;
    private SkillValidationPipeline validationPipeline;
    private MarkdownSkillParser markdownParser;
    private MarkdownSkillSerializer markdownSerializer;
    private SkillRegistry skillRegistry;
    private SkillConfigProperties config;
    private PromptRegistry promptRegistry;
    private ToolCapabilityManifest toolCapabilityManifest;
    private SkillTemplateLibrary templateLibrary;
    private SkillGenerator generator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        generationRouter = mock(GenerationRouter.class);
        validationPipeline = mock(SkillValidationPipeline.class);
        markdownParser = mock(MarkdownSkillParser.class);
        markdownSerializer = mock(MarkdownSkillSerializer.class);
        skillRegistry = mock(SkillRegistry.class);
        promptRegistry = mock(PromptRegistry.class);
        toolCapabilityManifest = mock(ToolCapabilityManifest.class);
        templateLibrary = mock(SkillTemplateLibrary.class);

        config = new SkillConfigProperties();
        config.setDirectory(tempDir.toString());

        lenient().when(promptRegistry.render(anyString(), anyMap())).thenReturn("生成 Prompt");
        lenient().when(skillRegistry.listSummaries()).thenReturn(List.of());
        lenient().when(toolCapabilityManifest.buildManifest()).thenReturn("## 能力清单");
        lenient().when(templateLibrary.findBestTemplate(any(SkillGap.class)))
                .thenReturn(new SkillTemplate("general", SkillTemplateLibrary.TemplateScene.GENERAL, "模板内容"));

        generator = new SkillGenerator(
                generationRouter,
                validationPipeline,
                markdownParser,
                markdownSerializer,
                skillRegistry,
                config,
                promptRegistry,
                toolCapabilityManifest,
                templateLibrary
        );
    }

    @Test
    void 生成成功时返回解析后的定义() {
        var gap = new SkillGap(0.8, "writing-assistant", "写作助手", "帮我写一篇文章", List.of("search"), "用户需要写作辅助");
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new com.lifepilot.llm.LlmResponse("""
                        ---
                        id: writing-assistant
                        name: 写作助手
                        description: 辅助用户进行写作
                        version: 1.0.0
                        suggested-tools:
                          - search
                        ---

                        # 写作助手

                        你是一个专业的写作助手。
                        """, 100, 200, "openai", "gpt-4", 500, false));
        when(validationPipeline.validate(anyString())).thenReturn(SkillValidationResult.allPassed());

        var definition = SkillDefinition.builder()
                .id("writing-assistant")
                .name("写作助手")
                .description("辅助用户进行写作")
                .version("1.0.0")
                .source(new SkillSource.UserDefined("/test", null))
                .instructions("你是一个专业的写作助手。")
                .suggestedTools(List.of("search"))
                .metadata(Map.of())
                .build();
        when(markdownParser.parse(anyString()))
                .thenReturn(new MarkdownSkillParser.ParseResult(true, definition, List.of(), Map.of()));

        var result = generator.generate(gap);

        assertThat(result.success()).isTrue();
        assertThat(result.definition()).isNotNull();
        assertThat(result.definition().id()).isEqualTo("writing-assistant");
        assertThat(result.definition().suggestedTools()).containsExactly("search");
        assertThat(result.definition().source()).isInstanceOf(SkillSource.AutoGenerated.class);
    }

    @Test
    void 验证失败时返回失败结果() {
        var gap = new SkillGap(0.7, "bad-skill", "坏 Skill", "请求", List.of(), "原因");
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new com.lifepilot.llm.LlmResponse("内容", 50, 100, "openai", "gpt-4", 300, false));
        when(validationPipeline.validate(anyString()))
                .thenReturn(SkillValidationResult.failed(
                        SkillValidationResult.ValidationStage.FORMAT,
                        List.of("缺少必填字段 id")
                ));

        var result = generator.generate(gap);

        assertThat(result.success()).isFalse();
        assertThat(result.validationResult()).isNotNull();
        assertThat(result.validationResult().failedStage())
                .isEqualTo(SkillValidationResult.ValidationStage.FORMAT);
    }

    @Test
    void 生成调用异常时返回错误结果() {
        var gap = new SkillGap(0.9, "fail-skill", "失败 Skill", "请求", List.of(), "原因");
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("生成服务不可用"));

        var result = generator.generate(gap);

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("生成服务不可用");
    }

    @Test
    void persistAndRegister会写入Skill文件并注册() throws IOException {
        var definition = SkillDefinition.builder()
                .id("auto-skill")
                .name("自动 Skill")
                .description("自动生成的 Skill")
                .version("1.0.0")
                .source(new SkillSource.AutoGenerated("trace-001", null, "用户请求", false))
                .instructions("自动生成的指令内容。")
                .suggestedTools(List.of("tool-a"))
                .metadata(Map.of())
                .build();

        when(markdownSerializer.serialize(any(SkillDefinition.class)))
                .thenReturn("---\nid: auto-skill\n---\n\n# 自动 Skill\n\n自动生成的指令内容。\n");
        when(skillRegistry.register(any(SkillDefinition.class))).thenReturn(true);

        boolean persisted = generator.persistAndRegister(definition);

        assertThat(persisted).isTrue();
        Path skillFile = tempDir.resolve("auto").resolve("auto-skill").resolve("SKILL.md");
        assertThat(skillFile).exists();
        assertThat(Files.readString(skillFile)).contains("auto-skill");
        verify(skillRegistry).register(any(SkillDefinition.class));
    }

    @Test
    void extractMarkdown可以提取Markdown代码块() {
        String wrapped = "```markdown\n---\nid: test\n---\n\n# Test\n\nContent\n```";

        String result = SkillGenerator.extractMarkdown(wrapped);

        assertThat(result).startsWith("---");
        assertThat(result).contains("id: test");
        assertThat(result).doesNotContain("```");
    }
}
