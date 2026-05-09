package com.lifepilot.memory.eval.judge;

import com.lifepilot.memory.eval.config.MemoryEvalProperties;
import com.lifepilot.memory.eval.loader.BenchmarkQuestion;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.function.BiFunction;

/**
 * LLM-as-judge。
 *
 * <p>适用于开放/多跳问答题。调用 {@code GenerationRouter}（scene = {@code eval}）判定
 * prediction 是否语义等价于 groundTruth。</p>
 *
 * <p>当 {@code lifepilot.memory.eval.judge.llm-enabled=false} 时，
 * {@link #judge} 直接返回 {@code JudgeVerdict.skipped(...)}，不触发 LLM 调用。</p>
 *
 * <p>为了不在本 spec 强依赖 {@code GenerationRouter} 的具体调用方式，
 * 本类通过 {@code BiFunction<String, String, String>} 作为"调用器"接收外部注入，
 * 由 {@code MemoryEvalAutoConfiguration} 在后续任务中装配真实 GenerationRouter 调用。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class LlmAsJudge implements AnswerJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmAsJudge.class);

    public static final String NAME = "llm";

    /**
     * LLM 调用器。输入 (scene, prompt)，返回原始响应文本。
     * 为 null 时等价于 disabled。
     */
    @Nullable
    private final BiFunction<String, String, String> llmCaller;

    private final MemoryEvalProperties.Judge judgeConfig;

    public LlmAsJudge(@Nullable BiFunction<String, String, String> llmCaller,
                      MemoryEvalProperties.Judge judgeConfig) {
        this.llmCaller = llmCaller;
        this.judgeConfig = judgeConfig;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JudgeVerdict judge(String prediction, String groundTruth, BenchmarkQuestion question) {
        if (!judgeConfig.isLlmEnabled() || llmCaller == null) {
            return JudgeVerdict.skipped(NAME, "LLM judge 未启用（llm-enabled=false）");
        }
        String prompt = buildPrompt(prediction, groundTruth, question);
        String response;
        try {
            response = llmCaller.apply(judgeConfig.getLlmScene(), prompt);
        } catch (RuntimeException e) {
            log.warn("LLM-as-judge 调用失败: questionId={}, error={}",
                    question.questionId(), e.getMessage());
            return JudgeVerdict.skipped(NAME, "LLM 调用异常: " + e.getMessage());
        }
        return parseVerdict(response);
    }

    static String buildPrompt(String prediction, String groundTruth, BenchmarkQuestion question) {
        return """
                你是一个评分员，判断 Agent 的回答是否语义上等价于标准答案。
                只返回 JSON 对象，不要任何额外文字。

                题目类型：%s
                问题：%s
                标准答案：%s
                Agent 回答：%s

                请输出 JSON，格式为：{"verdict": "correct"|"incorrect", "rationale": "一句话解释"}
                """.formatted(
                question.questionType().name(),
                nullToEmpty(question.query()),
                nullToEmpty(groundTruth),
                nullToEmpty(prediction));
    }

    private JudgeVerdict parseVerdict(String response) {
        if (response == null || response.isBlank()) {
            return JudgeVerdict.skipped(NAME, "LLM 返回为空");
        }
        String normalized = response.trim().toLowerCase(Locale.ROOT);
        // 宽松匹配：包含 correct / incorrect 关键字
        boolean correct = normalized.contains("\"correct\"")
                || normalized.contains("verdict: correct")
                || normalized.contains("\"verdict\":\"correct\"")
                || (normalized.contains("correct") && !normalized.contains("incorrect"));
        String rationale = extractRationale(response);
        return correct
                ? new JudgeVerdict(1.0f, NAME, false, rationale)
                : new JudgeVerdict(0.0f, NAME, false, rationale);
    }

    private static String extractRationale(String response) {
        int idx = response.indexOf("rationale");
        if (idx < 0) return response.length() > 200 ? response.substring(0, 200) : response;
        return response.substring(idx);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
