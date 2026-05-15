package com.lifepilot.skill.generation;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.event.SkillGeneratedEvent;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import com.lifepilot.skill.validation.SkillValidator;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Map;

/**
 * SkillSynthesizer 自生成管线测试。
 *
 * <p>覆盖 4 场景：</p>
 * <ul>
 *   <li>一次成功路径：LLM 首次生成合规 → 落库 + 发 Generated 事件</li>
 *   <li>迭代修正成功：首次非法 → 修正后合规（调用 LLM 两次）</li>
 *   <li>全部耗尽失败：超过 MAX_FIX_ATTEMPTS 仍非法 → 抛 {@link SkillSynthesisException}</li>
 *   <li>落盘目录：auto 子目录拼接 + 按 name 分桶</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class SkillSynthesizer_管线测试 {

    @TempDir
    Path tempDir;

    @Mock
    GenerationRouter generationRouter;
    @Mock
    PromptRegistry promptRegistry;
    @Mock
    ApplicationEventPublisher publisher;
    @Mock
    SkillInstallationRepository repository;
    @Mock
    DynamicToolRegistry toolRegistry;
    @Mock
    ZhiweiPaths zhiweiPaths;

    SkillSynthesizer synthesizer;

    @BeforeEach
    void setup() {
        var parser = new MarkdownSkillParser();
        var descriptionValidator = new SkillDescriptionValidator();
        var bodyValidator = new SkillBodyValidator();
        var validator = new SkillValidator(descriptionValidator, bodyValidator, toolRegistry);
        var installer = new SkillInstaller(parser, descriptionValidator, bodyValidator, repository);
        var config = new SkillConfigProperties();
        when(zhiweiPaths.home("skills")).thenReturn(tempDir);

        synthesizer = new SkillSynthesizer(
                generationRouter, promptRegistry, parser, validator, installer, publisher, config, zhiweiPaths);

        // 测试只关心行为契约，promptRegistry 返回固定字符串即可（真实模板由 C.4 补）
        lenient().when(promptRegistry.render(anyString(), any())).thenReturn("rendered");
        // 默认无工具：validateGenerated 对空 suggestedTools 直接通过
        lenient().when(toolRegistry.resolve(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void 一次生成通过校验应直接落库并发Generated事件() {
        stubLlmOnce(validMd("ai-1"));

        SkillInstallation install = synthesizer.synthesize(new SkillSynthesisContext(
                "用户需要 AI 帮忙管理 todo",
                "todo 管理场景",
                List.of()));

        assertThat(install.name()).isEqualTo("ai-1");
        assertThat(install.sourceType()).isEqualTo(SkillSourceType.AUTO_GENERATED);
        assertThat(install.enabled()).isTrue();

        ArgumentCaptor<SkillGeneratedEvent> captor = ArgumentCaptor.forClass(SkillGeneratedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        SkillGeneratedEvent event = captor.getValue();
        assertThat(event.skillName()).isEqualTo("ai-1");
        assertThat(event.sourceType()).isEqualTo(SkillSourceType.AUTO_GENERATED);
        assertThat(event.at()).isNotNull();

        verify(repository).upsert(any());
        // 仅调用一次 LLM
        verify(generationRouter, times(1)).call(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void 首次违规应迭代修正一次() {
        // parse 失败 → fix → 第二次返回合法
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(llmResponse("not a skill markdown"))
                .thenReturn(llmResponse(validMd("ai-fixed")));

        SkillInstallation install = synthesizer.synthesize(new SkillSynthesisContext(
                "desc", "scenario", List.of()));

        assertThat(install.name()).isEqualTo("ai-fixed");
        verify(generationRouter, times(2))
                .call(anyString(), anyString(), any(), any(), any(), any(), any());
        // 分别渲染 synthesis 和 fix 两个模板
        verify(promptRegistry).render(eq("generation/skill-synthesis"), any());
        verify(promptRegistry).render(eq("generation/skill-fix"), any());
    }

    @Test
    void 超过修正次数应抛出SkillSynthesisException() {
        // 三次全部非法（首次 + 两次修正）
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(llmResponse("garbage one"))
                .thenReturn(llmResponse("garbage two"))
                .thenReturn(llmResponse("garbage three"));

        assertThatThrownBy(() -> synthesizer.synthesize(new SkillSynthesisContext(
                "desc", "scenario", List.of())))
                .isInstanceOf(SkillSynthesisException.class)
                .hasMessageContaining("失败");

        verify(generationRouter, times(3))
                .call(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void 生成的skill应写入auto子目录() {
        stubLlmOnce(validMd("dir-test"));

        SkillInstallation install = synthesizer.synthesize(new SkillSynthesisContext(
                "desc", "scenario", List.of()));

        // 目标目录应为 {tempDir}/auto/dir-test
        Path expected = tempDir.resolve("auto").resolve("dir-test");
        assertThat(install.filePath()).isEqualTo(expected.toString());
        assertThat(expected.resolve("SKILL.md")).exists();
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private void stubLlmOnce(String content) {
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(llmResponse(content));
    }

    private LlmResponse llmResponse(String content) {
        return new LlmResponse(content, null, null, List.of(), Map.of(), 0, 0, null, 0, "test", "test-model", 0L, false);
    }

    /** 生成一份最小合规 SKILL.md（不含 suggested_tools，避免触发工具校验）。 */
    private String validMd(String name) {
        return """
                ---
                name: %s
                description: 当需要 AI 生成时使用。关键词 ai
                version: 1.0.0
                ---
                ## 适用场景
                - a
                ## 不适用场景
                - b
                ## 工作流
                1. do
                """.formatted(name);
    }
}
