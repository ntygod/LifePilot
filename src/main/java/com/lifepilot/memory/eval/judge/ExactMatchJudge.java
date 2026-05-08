package com.lifepilot.memory.eval.judge;

import com.lifepilot.memory.eval.loader.BenchmarkQuestion;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 精确匹配判定器。
 *
 * <p>适用于单跳事实题和知识更新题：归一化后字符串完全相等 → 1，否则 0。</p>
 *
 * <p>归一化规则：unicode NFKC、转小写、去中英文标点、去多余空白。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class ExactMatchJudge implements AnswerJudge {

    public static final String NAME = "exact-match";

    /** 中英文标点匹配，NFKC 归一化之后直接剥离。 */
    private static final Pattern PUNCT_PATTERN = Pattern.compile(
            "[\\p{Punct}\\u3000-\\u303F\\uFF00-\\uFFEF]");

    /** 连续空白归一为单个空格。 */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JudgeVerdict judge(String prediction, String groundTruth, BenchmarkQuestion question) {
        String normalizedPred = normalize(prediction);
        String normalizedTruth = normalize(groundTruth);
        if (normalizedPred.isEmpty() && normalizedTruth.isEmpty()) {
            return JudgeVerdict.correct(NAME);
        }
        return normalizedPred.equals(normalizedTruth)
                ? JudgeVerdict.correct(NAME)
                : JudgeVerdict.incorrect(NAME);
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase();
        s = PUNCT_PATTERN.matcher(s).replaceAll(" ");
        s = WHITESPACE_PATTERN.matcher(s).replaceAll(" ").trim();
        return s;
    }
}
