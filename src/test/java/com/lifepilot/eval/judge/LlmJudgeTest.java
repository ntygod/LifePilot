package com.lifepilot.eval.judge;

import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmJudge 单元测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class LlmJudgeTest {

    private GenerationRouter generationRouter;
    private EvalConfigProperties config;
    private PromptRegistry promptRegistry;
    private LlmJudge judge;

    @BeforeEach
    void setUp() {
        generationRouter = mock(GenerationRouter.class);
        config = new EvalConfigProperties();
        promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");
        judge = new LlmJudge(generationRouter, config, promptRegistry);
    }

    private static LlmResponse buildResponse(String content, int inputTokens, int outputTokens) {
        return new LlmResponse(content, inputTokens, outputTokens, "test-provider", "test-model", 100L, false);
    }

    @Test
    void 正常结构化响应时直接返回评分() {
        var judgeResponse = new LlmJudge.JudgeResponse(0.85, "输出质量良好", null, null);
        when(generationRouter.callEntity(anyString(), anyString(), eq(LlmJudge.JudgeResponse.class), any(), any(), any()))
                .thenReturn(judgeResponse);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.85, result.score(), 0.0001);
        assertEquals("输出质量良好", result.justification());
        assertEquals(0, result.tokensUsed());
        assertFalse(result.fallback());
        verify(generationRouter, never()).call(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void 结构化解析返回空时降级到手动解析() {
        when(generationRouter.callEntity(anyString(), anyString(), eq(LlmJudge.JudgeResponse.class), any(), any(), any()))
                .thenReturn(null);
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(buildResponse("0.65", 30, 10));

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.65, result.score(), 0.0001);
        assertFalse(result.fallback());
    }

    @Test
    void 调用异常时返回降级结果() {
        when(generationRouter.callEntity(anyString(), anyString(), eq(LlmJudge.JudgeResponse.class), any(), any(), any()))
                .thenReturn(null);
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new LlmUnavailableException("连接超时", "eval-judge", List.of("provider-1")));

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.5, result.score(), 0.0001);
        assertTrue(result.justification().contains("LLM 调用失败"));
        assertTrue(result.fallback());
    }

    @Test
    void 手动解析失败后会用简化提示重试() {
        when(generationRouter.callEntity(anyString(), anyString(), eq(LlmJudge.JudgeResponse.class), any(), any(), any()))
                .thenReturn(null);
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(buildResponse("这是一段无法解析为评分的文本", 50, 30))
                .thenReturn(buildResponse("0.75", 20, 10));

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.75, result.score(), 0.0001);
        assertEquals(110, result.tokensUsed());
        assertFalse(result.fallback());
        verify(generationRouter, times(2)).call(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void 重试也失败时返回最终降级评分() {
        when(generationRouter.callEntity(anyString(), anyString(), eq(LlmJudge.JudgeResponse.class), any(), any(), any()))
                .thenReturn(null);
        when(generationRouter.call(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(buildResponse("完全无法解析", 50, 30))
                .thenReturn(buildResponse("依然无法解析", 20, 10));

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.5, result.score(), 0.0001);
        assertTrue(result.justification().contains("降级评分"));
        assertTrue(result.fallback());
        assertEquals(110, result.tokensUsed());
    }

    @Test
    void 评分会被裁剪到零到一之间() {
        when(generationRouter.callEntity(anyString(), anyString(), eq(LlmJudge.JudgeResponse.class), any(), any(), any()))
                .thenReturn(new LlmJudge.JudgeResponse(1.5, "超过范围", null, null))
                .thenReturn(new LlmJudge.JudgeResponse(-0.3, "低于范围", null, null));

        var high = judge.judge("实际输出", "期望模式", "评估标准");
        var low = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(1.0, high.score(), 0.0001);
        assertEquals(0.0, low.score(), 0.0001);
    }
}
