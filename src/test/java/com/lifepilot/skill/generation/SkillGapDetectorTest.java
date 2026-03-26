package com.lifepilot.skill.generation;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SkillGapDetector 单元测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
@ExtendWith(MockitoExtension.class)
class SkillGapDetectorTest {

    @Mock
    private SkillRegistry skillRegistry;

    @Mock
    private GenerationRouter generationRouter;

    @Mock
    private PromptRegistry promptRegistry;

    private SkillConfigProperties config;
    private SkillGapDetector detector;

    @BeforeEach
    void setUp() {
        config = new SkillConfigProperties();
        lenient().when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");
        detector = new SkillGapDetector(skillRegistry, generationRouter, config, promptRegistry);
    }

    @Test
    void 注册表为空时直接判定为缺口() {
        when(skillRegistry.listSummaries()).thenReturn(List.of());

        var result = detector.detectGap("帮我查天气");

        assertThat(result).isPresent();
        assertThat(result.get().confidence()).isEqualTo(0.9);
        assertThat(result.get().reason()).isEqualTo("注册表为空，无可用 Skill");
        verifyNoInteractions(generationRouter);
    }

    @Test
    void 搜索命中时返回空结果() {
        when(skillRegistry.listSummaries()).thenReturn(List.of("weather: 查询天气"));
        when(skillRegistry.search("帮我查天气")).thenReturn(List.of(org.mockito.Mockito.mock(SkillDefinition.class)));

        var result = detector.detectGap("帮我查天气");

        assertThat(result).isEmpty();
        verifyNoInteractions(generationRouter);
    }

    @Test
    void 搜索无结果时调用生成路由分析缺口() {
        when(skillRegistry.listSummaries()).thenReturn(List.of("todo: 待办管理"));
        when(skillRegistry.search("帮我查汇率")).thenReturn(List.of());
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new LlmResponse("""
                        {
                          "suggestedId": "exchange-rate",
                          "suggestedName": "汇率查询",
                          "suggestedTools": ["http-request"],
                          "reason": "用户需要查询汇率，当前没有相关 Skill"
                        }
                        """, 100, 200, "provider-1", "model-1", 500, false));

        var result = detector.detectGap("帮我查汇率");

        assertThat(result).isPresent();
        assertThat(result.get().suggestedId()).isEqualTo("exchange-rate");
        assertThat(result.get().suggestedName()).isEqualTo("汇率查询");
        assertThat(result.get().suggestedTools()).containsExactly("http-request");
    }

    @Test
    void 生成路由失败时返回空结果() {
        when(skillRegistry.listSummaries()).thenReturn(List.of("todo: 待办管理"));
        when(skillRegistry.search("帮我查汇率")).thenReturn(List.of());
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new LlmUnavailableException("无可用 Provider", "skill_generation", List.of()));

        var result = detector.detectGap("帮我查汇率");

        assertThat(result).isEmpty();
    }

    @Test
    void 非法Json时返回空结果() {
        when(skillRegistry.listSummaries()).thenReturn(List.of("todo: 待办管理"));
        when(skillRegistry.search("帮我查汇率")).thenReturn(List.of());
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new LlmResponse("这不是 JSON", 50, 30, "provider-1", "model-1", 300, false));

        var result = detector.detectGap("帮我查汇率");

        assertThat(result).isEmpty();
    }

    @Test
    void extractJson可以提取Markdown代码块() {
        String content = """
                ```json
                {"key": "value"}
                ```
                """;

        assertThat(SkillGapDetector.extractJson(content)).isEqualTo("{\"key\": \"value\"}");
    }

    @Test
    void generateSuggestedId带有Auto前缀() {
        assertThat(SkillGapDetector.generateSuggestedId("帮我查天气")).startsWith("auto-");
    }
}
