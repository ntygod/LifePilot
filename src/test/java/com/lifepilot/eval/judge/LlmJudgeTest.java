package com.lifepilot.eval.judge;

import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
/**
 * LlmJudge 降级与重试单元测试。
 *
 * <p>Mock LlmRouter，验证正常解析、超时降级、不可解析响应重试、评分裁剪等场景。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
class LlmJudgeTest {

    private LlmRouter llmRouter;
    private EvalConfigProperties config;
    private PromptRegistry promptRegistry;
    private LlmJudge judge;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        config = new EvalConfigProperties();
        promptRegistry = mock(PromptRegistry.class);
        // 模板渲染返回简单提示词，不影响 LLM 调用逻辑测试
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");
        judge = new LlmJudge(llmRouter, config, promptRegistry);
    }

    /** 构建 LlmResponse 辅助方法。 */
    private static LlmResponse buildResponse(String content, int inputTokens, int outputTokens) {
        return new LlmResponse(content, inputTokens, outputTokens, "test-provider", "test-model", 100L, false);
    }

    // --- 正常解析场景 ---

    @Test
    void 正常JSON响应_解析成功() {
        // callEntity 直接返回结构化结果
        var judgeResponse = new LlmJudge.JudgeResponse(0.85, "输出质量良好", null, null);
        when(llmRouter.callEntity(any(LlmRequest.class), eq(LlmJudge.JudgeResponse.class)))
                .thenReturn(judgeResponse);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.85, result.score(), 0.0001);
        assertEquals("输出质量良好", result.justification());
        assertEquals(0, result.tokensUsed()); // callEntity 不返回 token 使用量
        assertFalse(result.fallback());
        verify(llmRouter, never()).call(any(LlmRequest.class));
    }

    @Test
    void JSON包裹在markdown代码块中_解析成功() {
        // callEntity 直接返回结构化结果（无需关心 markdown 包裹）
        var judgeResponse = new LlmJudge.JudgeResponse(0.9, "非常好的回答", null, null);
        when(llmRouter.callEntity(any(LlmRequest.class), eq(LlmJudge.JudgeResponse.class)))
                .thenReturn(judgeResponse);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.9, result.score(), 0.0001);
        assertEquals("非常好的回答", result.justification());
        assertFalse(result.fallback());
    }

    @Test
    void callEntity返回null_降级到手动解析_纯数字响应() {
        // callEntity 返回 null，降级到 call() 手动解析
        when(llmRouter.callEntity(any(LlmRequest.class), eq(LlmJudge.JudgeResponse.class)))
                .thenReturn(null);
        var response = buildResponse("0.65", 30, 10);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(response);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.65, result.score(), 0.0001);
        assertFalse(result.fallback());
    }

    // --- 异常降级场景 ---

    @Test
    void LLM调用异常_返回降级结果() {
        // callEntity 抛异常，降级到 fallbackToManualParse，call() 也抛异常
        when(llmRouter.callEntity(any(LlmRequest.class), eq(LlmJudge.JudgeResponse.class)))
                .thenReturn(null);
        when(llmRouter.call(any(LlmRequest.class)))
                .thenThrow(new LlmUnavailableException("连接超时", "eval-judge", List.of("provider-1")));

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.5, result.score(), 0.0001);
        assertTrue(result.justification().contains("LLM 调用失败"));
        assertEquals(0, result.tokensUsed());
        assertTrue(result.fallback());
    }

    // --- 重试场景 ---

    @Test
    void callEntity返回null_手动解析失败_重试成功() {
        when(llmRouter.callEntity(any(LlmRequest.class), eq(LlmJudge.JudgeResponse.class)))
                .thenReturn(null);
        var gibberishResponse = buildResponse("这是一段无法解析为评分的文本，没有任何数字", 50, 30);
        var retryResponse = buildResponse("0.75", 20, 10);
        when(llmRouter.call(any(LlmRequest.class)))
                .thenReturn(gibberishResponse)
                .thenReturn(retryResponse);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.75, result.score(), 0.0001);
        assertFalse(result.fallback());
        assertEquals(110, result.tokensUsed());
        verify(llmRouter, times(2)).call(any(LlmRequest.class));
    }

    @Test
    void callEntity返回null_手动解析和重试都失败_返回降级结果() {
        when(llmRouter.callEntity(any(LlmRequest.class), eq(LlmJudge.JudgeResponse.class)))
                .thenReturn(null);
        var gibberishResponse1 = buildResponse("完全无法解析的乱码内容，没有数字", 50, 30);
        var gibberishResponse2 = buildResponse("依然无法解析，还是没有数字", 20, 10);
        when(llmRouter.call(any(LlmRequest.class)))
                .thenReturn(gibberishResponse1)
                .thenReturn(gibberishResponse2);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.5, result.score(), 0.0001);
        assertTrue(result.justification().contains("降级评分"));
        assertTrue(result.fallback());
        assertEquals(110, result.tokensUsed());
        verify(llmRouter, times(2)).call(any(LlmRequest.class));
    }

    // --- 评分裁剪场景 ---

    @Test
    void callEntity评分超过1_裁剪到1() {
        var judgeResponse = new LlmJudge.JudgeResponse(1.5, "超出范围", null, null);
        when(llmRouter.callEntity(any(LlmRequest.class), eq(LlmJudge.JudgeResponse.class)))
                .thenReturn(judgeResponse);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(1.0, result.score(), 0.0001);
        assertFalse(result.fallback());
    }

    @Test
    void callEntity评分为负数_裁剪到0() {
        var judgeResponse = new LlmJudge.JudgeResponse(-0.3, "负数评分", null, null);
        when(llmRouter.callEntity(any(LlmRequest.class), eq(LlmJudge.JudgeResponse.class)))
                .thenReturn(judgeResponse);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.0, result.score(), 0.0001);
        assertFalse(result.fallback());
    }
}
