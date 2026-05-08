package com.lifepilot.memory.eval.judge;

import com.lifepilot.memory.eval.config.MemoryEvalProperties;
import com.lifepilot.memory.eval.loader.BenchmarkQuestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmAsJudge} 单元测试。
 *
 * @author zsg
 * @since 2026-05-09
 */
@DisplayName("LlmAsJudge 单元测试")
class LlmAsJudge测试 {

    @Test
    @DisplayName("llm-enabled=false: 直接 skipped 且不调用 LLM")
    void llm_未启用_跳过() {
        MemoryEvalProperties.Judge cfg = new MemoryEvalProperties.Judge();
        cfg.setLlmEnabled(false);
        AtomicInteger calls = new AtomicInteger();
        LlmAsJudge judge = new LlmAsJudge((scene, prompt) -> {
            calls.incrementAndGet();
            return "{\"verdict\":\"correct\"}";
        }, cfg);

        JudgeVerdict v = judge.judge("A", "A", question());
        assertThat(v.skipped()).isTrue();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("caller 为 null: skipped 且安全降级")
    void caller_为null_跳过() {
        MemoryEvalProperties.Judge cfg = new MemoryEvalProperties.Judge();
        cfg.setLlmEnabled(true);
        LlmAsJudge judge = new LlmAsJudge(null, cfg);

        JudgeVerdict v = judge.judge("A", "B", question());
        assertThat(v.skipped()).isTrue();
    }

    @Test
    @DisplayName("LLM 返回 correct → score=1")
    void llm_正确判定() {
        MemoryEvalProperties.Judge cfg = new MemoryEvalProperties.Judge();
        cfg.setLlmEnabled(true);
        LlmAsJudge judge = new LlmAsJudge(
                (scene, prompt) -> "{\"verdict\":\"correct\",\"rationale\":\"语义等价\"}",
                cfg);

        JudgeVerdict v = judge.judge("兔子", "小兔子", question());
        assertThat(v.skipped()).isFalse();
        assertThat(v.score()).isEqualTo(1.0f);
        assertThat(v.rationale()).isNotBlank();
    }

    @Test
    @DisplayName("LLM 返回 incorrect → score=0")
    void llm_错误判定() {
        MemoryEvalProperties.Judge cfg = new MemoryEvalProperties.Judge();
        cfg.setLlmEnabled(true);
        LlmAsJudge judge = new LlmAsJudge(
                (scene, prompt) -> "{\"verdict\":\"incorrect\",\"rationale\":\"完全不同\"}",
                cfg);

        JudgeVerdict v = judge.judge("苹果", "香蕉", question());
        assertThat(v.score()).isEqualTo(0.0f);
    }

    @Test
    @DisplayName("LLM 调用抛异常: skipped + 日志不抛出")
    void llm_异常_跳过() {
        MemoryEvalProperties.Judge cfg = new MemoryEvalProperties.Judge();
        cfg.setLlmEnabled(true);
        LlmAsJudge judge = new LlmAsJudge(
                (scene, prompt) -> { throw new RuntimeException("network down"); },
                cfg);

        JudgeVerdict v = judge.judge("A", "A", question());
        assertThat(v.skipped()).isTrue();
        assertThat(v.rationale()).contains("network down");
    }

    @Test
    @DisplayName("LLM 返回为空: skipped")
    void llm_返回空_跳过() {
        MemoryEvalProperties.Judge cfg = new MemoryEvalProperties.Judge();
        cfg.setLlmEnabled(true);
        LlmAsJudge judge = new LlmAsJudge(
                (scene, prompt) -> "",
                cfg);

        JudgeVerdict v = judge.judge("A", "A", question());
        assertThat(v.skipped()).isTrue();
    }

    @Test
    @DisplayName("buildPrompt 包含 question / groundTruth / prediction")
    void 构造prompt_包含必要字段() {
        String prompt = LlmAsJudge.buildPrompt(
                "pred-val", "truth-val",
                new BenchmarkQuestion("q", "q-query", "truth-val",
                        BenchmarkQuestion.QuestionType.MULTI_HOP, List.of()));
        assertThat(prompt).contains("pred-val");
        assertThat(prompt).contains("truth-val");
        assertThat(prompt).contains("q-query");
        assertThat(prompt).contains("MULTI_HOP");
    }

    private BenchmarkQuestion question() {
        return new BenchmarkQuestion("q", "test-question", "truth",
                BenchmarkQuestion.QuestionType.OPEN_ENDED, List.of());
    }
}
