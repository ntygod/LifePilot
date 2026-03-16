package com.lifepilot.skill.generation;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link SkillGapDetector} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
@ExtendWith(MockitoExtension.class)
class SkillGapDetectorTest {

    @Mock
    private SkillRegistry skillRegistry;

    @Mock
    private LlmRouter llmRouter;

    @Mock
    private PromptRegistry promptRegistry;

    private SkillConfigProperties config;
    private SkillGapDetector detector;

    @BeforeEach
    void setUp() {
        config = new SkillConfigProperties();
        lenient().when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");
        detector = new SkillGapDetector(skillRegistry, llmRouter, config, promptRegistry);
    }

    // ─────────────────────────────────────────────
    //  detectGap — 注册表为空
    // ─────────────────────────────────────────────

    @Test
    void detectGap_注册表为空时直接判定缺口_置信度09() {
        when(skillRegistry.listSummaries()).thenReturn(List.of());

        var result = detector.detectGap("帮我查天气");

        assertThat(result).isPresent();
        var gap = result.get();
        assertThat(gap.confidence()).isEqualTo(0.9);
        assertThat(gap.triggerRequest()).isEqualTo("帮我查天气");
        assertThat(gap.reason()).isEqualTo("注册表为空，无可用 Skill");
        assertThat(gap.suggestedId()).startsWith("auto-");
        // 不应调用 LLM
        verifyNoInteractions(llmRouter);
    }

    // ─────────────────────────────────────────────
    //  detectGap — 搜索命中
    // ─────────────────────────────────────────────

    @Test
    void detectGap_搜索返回结果时无缺口() {
        when(skillRegistry.listSummaries()).thenReturn(List.of("weather: 查询天气"));
        when(skillRegistry.search("帮我查天气")).thenReturn(List.of(mock(SkillDefinition.class)));

        var result = detector.detectGap("帮我查天气");

        assertThat(result).isEmpty();
        // 不应调用 LLM
        verifyNoInteractions(llmRouter);
    }

    // ─────────────────────────────────────────────
    //  detectGap — 搜索无结果，LLM 分析成功
    // ─────────────────────────────────────────────

    @Test
    void detectGap_搜索无结果_LLM分析成功返回SkillGap() {
        when(skillRegistry.listSummaries()).thenReturn(List.of("todo: 待办管理"));
        when(skillRegistry.search("帮我查汇率")).thenReturn(List.of());

        String llmJson = """
                {
                  "suggestedId": "exchange-rate",
                  "suggestedName": "汇率查询",
                  "suggestedTools": ["http-request"],
                  "reason": "用户需要查询汇率，当前无相关 Skill"
                }
                """;
        var llmResponse = new LlmResponse(llmJson, 100, 200, "provider-1", "gpt-4", 500, false);
        when(llmRouter.call(any(LlmRequest.class)))
                .thenReturn(llmResponse);

        var result = detector.detectGap("帮我查汇率");

        assertThat(result).isPresent();
        var gap = result.get();
        assertThat(gap.suggestedId()).isEqualTo("exchange-rate");
        assertThat(gap.suggestedName()).isEqualTo("汇率查询");
        assertThat(gap.suggestedTools()).containsExactly("http-request");
        assertThat(gap.reason()).isEqualTo("用户需要查询汇率，当前无相关 Skill");
        assertThat(gap.triggerRequest()).isEqualTo("帮我查汇率");
    }

    @Test
    void detectGap_LLM返回markdown代码块包裹的JSON() {
        when(skillRegistry.listSummaries()).thenReturn(List.of("todo: 待办管理"));
        when(skillRegistry.search("帮我翻译文档")).thenReturn(List.of());

        String llmContent = """
                ```json
                {
                  "suggestedId": "doc-translator",
                  "suggestedName": "文档翻译",
                  "suggestedTools": ["http-request", "file-read"],
                  "reason": "需要文档翻译能力"
                }
                ```
                """;
        var llmResponse = new LlmResponse(llmContent, 80, 150, "provider-1", "gpt-4", 400, false);
        when(llmRouter.call(any(LlmRequest.class)))
                .thenReturn(llmResponse);

        var result = detector.detectGap("帮我翻译文档");

        assertThat(result).isPresent();
        assertThat(result.get().suggestedId()).isEqualTo("doc-translator");
        assertThat(result.get().suggestedTools()).containsExactly("http-request", "file-read");
    }

    // ─────────────────────────────────────────────
    //  detectGap — LLM 调用失败降级
    // ─────────────────────────────────────────────

    @Test
    void detectGap_LLM调用失败返回空结果_记录WARN日志() {
        when(skillRegistry.listSummaries()).thenReturn(List.of("todo: 待办管理"));
        when(skillRegistry.search("帮我查汇率")).thenReturn(List.of());
        when(llmRouter.call(any(LlmRequest.class)))
                .thenThrow(new LlmUnavailableException("无可用 Provider", "skill_generation", List.of()));

        var result = detector.detectGap("帮我查汇率");

        assertThat(result).isEmpty();
    }

    @Test
    void detectGap_LLM返回非法JSON时降级返回空结果() {
        when(skillRegistry.listSummaries()).thenReturn(List.of("todo: 待办管理"));
        when(skillRegistry.search("帮我查汇率")).thenReturn(List.of());

        var llmResponse = new LlmResponse("这不是JSON", 50, 30, "provider-1", "gpt-4", 300, false);
        when(llmRouter.call(any(LlmRequest.class)))
                .thenReturn(llmResponse);

        var result = detector.detectGap("帮我查汇率");

        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────
    //  extractJson — 静态方法测试
    // ─────────────────────────────────────────────

    @Test
    void extractJson_纯JSON直接返回() {
        String json = """
                {"key": "value"}
                """;
        assertThat(SkillGapDetector.extractJson(json)).isEqualTo("{\"key\": \"value\"}");
    }

    @Test
    void extractJson_markdown代码块提取JSON() {
        String content = """
                ```json
                {"key": "value"}
                ```
                """;
        assertThat(SkillGapDetector.extractJson(content)).isEqualTo("{\"key\": \"value\"}");
    }

    // ─────────────────────────────────────────────
    //  generateSuggestedId — 静态方法测试
    // ─────────────────────────────────────────────

    @Test
    void generateSuggestedId_以auto前缀开头() {
        String id = SkillGapDetector.generateSuggestedId("帮我查天气");
        assertThat(id).startsWith("auto-");
    }
}
