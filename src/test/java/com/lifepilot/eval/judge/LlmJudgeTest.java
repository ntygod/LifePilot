package com.lifepilot.eval.judge;

import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
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
    private LlmJudge judge;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        config = new EvalConfigProperties();
        judge = new LlmJudge(llmRouter, config);
    }

    /** 构建 LlmResponse 辅助方法。 */
    private static LlmResponse buildResponse(String content, int inputTokens, int outputTokens) {
        return new LlmResponse(content, inputTokens, outputTokens, "test-provider", "test-model", 100L, false);
    }

    // --- 正常解析场景 ---

    @Test
    void 正常JSON响应_解析成功() {
        var response = buildResponse("""
                {"score": 0.85, "justification": "输出质量良好"}
                """, 50, 30);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(response);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.85, result.score(), 0.0001);
        assertEquals("输出质量良好", result.justification());
        assertEquals(80, result.tokensUsed());
        assertFalse(result.fallback());
        verify(llmRouter, times(1)).call(any(LlmRequest.class));
    }

    @Test
    void JSON包裹在markdown代码块中_解析成功() {
        var response = buildResponse("""
                ```json
                {"score": 0.9, "justification": "非常好的回答"}
                ```
                """, 40, 20);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(response);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.9, result.score(), 0.0001);
        assertEquals("非常好的回答", result.justification());
        assertFalse(result.fallback());
    }

    @Test
    void 纯数字响应_通过正则提取评分() {
        var response = buildResponse("0.65", 30, 10);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(response);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.65, result.score(), 0.0001);
        assertFalse(result.fallback());
    }

    // --- 异常降级场景 ---

    @Test
    void LLM调用异常_返回降级结果() {
        when(llmRouter.call(any(LlmRequest.class)))
                .thenThrow(new LlmUnavailableException("连接超时", "eval-judge", List.of("provider-1")));

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.5, result.score(), 0.0001);
        assertTrue(result.justification().contains("LLM 调用失败"));
        assertEquals(0, result.tokensUsed());
        assertTrue(result.fallback());
        verify(llmRouter, times(1)).call(any(LlmRequest.class));
    }

    // --- 重试场景 ---

    @Test
    void 不可解析响应_重试成功() {
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
    void 重试也失败_返回降级结果() {
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
    void 评分超过1_裁剪到1() {
        var response = buildResponse("""
                {"score": 1.5, "justification": "超出范围"}
                """, 40, 20);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(response);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(1.0, result.score(), 0.0001);
        assertFalse(result.fallback());
    }

    @Test
    void 评分为负数_裁剪到0() {
        var response = buildResponse("""
                {"score": -0.3, "justification": "负数评分"}
                """, 40, 20);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(response);

        var result = judge.judge("实际输出", "期望模式", "评估标准");

        assertEquals(0.0, result.score(), 0.0001);
        assertFalse(result.fallback());
    }
}
