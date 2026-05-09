package com.lifepilot.memory.eval.judge;

import com.lifepilot.memory.eval.loader.BenchmarkQuestion;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 词级 F1 判定器。
 *
 * <p>适用于多跳/时序/开放问答题。归一化后按空白 + 常用分隔切词，对比重合度计算 P/R/F1。</p>
 *
 * <p>对中文文本，按字符切分（每个汉字作为一个 token）+ 保留英文词。常用停用词剥离，降低噪音。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class F1Judge implements AnswerJudge {

    public static final String NAME = "f1";

    /** 英文 + 中文常见停用词。只做基础剥离，避免"的 是 了"吃掉主要信号。 */
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
            "of", "in", "on", "at", "to", "for", "with", "by", "and", "or", "but",
            "that", "this", "these", "those", "it", "its",
            "的", "了", "是", "有", "在", "和", "与", "或", "及"
    );

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public JudgeVerdict judge(String prediction, String groundTruth, BenchmarkQuestion question) {
        List<String> predTokens = tokenize(prediction);
        List<String> truthTokens = tokenize(groundTruth);
        if (predTokens.isEmpty() && truthTokens.isEmpty()) {
            return JudgeVerdict.correct(NAME);
        }
        if (predTokens.isEmpty() || truthTokens.isEmpty()) {
            return JudgeVerdict.incorrect(NAME);
        }

        Map<String, Integer> predCount = toMultiset(predTokens);
        Map<String, Integer> truthCount = toMultiset(truthTokens);

        int overlap = 0;
        for (Map.Entry<String, Integer> entry : predCount.entrySet()) {
            Integer truthCnt = truthCount.get(entry.getKey());
            if (truthCnt != null) {
                overlap += Math.min(entry.getValue(), truthCnt);
            }
        }
        if (overlap == 0) {
            return JudgeVerdict.incorrect(NAME);
        }

        float precision = (float) overlap / predTokens.size();
        float recall = (float) overlap / truthTokens.size();
        float f1 = 2f * precision * recall / (precision + recall);
        return JudgeVerdict.partial(NAME, f1);
    }

    static List<String> tokenize(String raw) {
        String normalized = ExactMatchJudge.normalize(raw);
        if (normalized.isEmpty()) return List.of();
        // 先按空白切
        String[] words = normalized.split(" ");
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (String w : words) {
            if (w.isBlank()) continue;
            // 中文字符逐字切分，英文保留原词
            boolean hasCjk = w.chars().anyMatch(c ->
                    (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF));
            if (hasCjk) {
                for (int i = 0; i < w.length(); i++) {
                    char ch = w.charAt(i);
                    if (Character.isWhitespace(ch)) continue;
                    String token = String.valueOf(ch);
                    if (!STOP_WORDS.contains(token)) tokens.add(token);
                }
            } else {
                if (!STOP_WORDS.contains(w)) tokens.add(w);
            }
        }
        return tokens;
    }

    private static Map<String, Integer> toMultiset(List<String> tokens) {
        Map<String, Integer> m = new HashMap<>();
        for (String t : tokens) m.merge(t, 1, Integer::sum);
        return m;
    }

    // 防止 IDE 误报
    @SuppressWarnings("unused")
    private static Set<String> uniqueSet(List<String> list) {
        return new HashSet<>(list);
    }

    @SuppressWarnings("unused")
    private static List<String> dummy() {
        return Arrays.asList();
    }
}
