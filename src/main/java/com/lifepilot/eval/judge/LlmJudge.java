package com.lifepilot.eval.judge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 评判器 — 使用 LLM 评估语义维度。
 *
 * <p>构造评估 Prompt 发送给 LLM，解析响应为结构化评分和理由。
 * 支持多次采样取平均、三级降级策略。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);

    /** 匹配 0.0 ~ 1.0 范围内的小数（含整数 0 和 1）。 */
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+\\.?\\d*)");

    private final GenerationRouter generationRouter;
    private final EvalConfigProperties config;
    private final PromptRegistry promptRegistry;

    public LlmJudge(GenerationRouter generationRouter, EvalConfigProperties config, PromptRegistry promptRegistry) {
        this.generationRouter = generationRouter;
        this.config = config;
        this.promptRegistry = promptRegistry;
        log.info("LlmJudge 初始化完成: scene={}", config.getLlmJudge().getScene());
    }

    /**
     * LLM 语义评估。支持多次采样取平均。
     *
     * @param actualOutput    Agent 实际输出
     * @param expectedPattern 期望输出模式
     * @param criteria        评估标准
     * @return 评判结果（评分 + 理由 + 子维度 + 建议）
     */
    public JudgeResult judge(String actualOutput, String expectedPattern, String criteria) {
        int sampleCount = config.getLlmJudge().getSampleCount();
        if (sampleCount <= 1) {
            return judgeSingle(actualOutput, expectedPattern, criteria);
        }

        // 多次采样取平均
        List<JudgeResult> samples = new ArrayList<>();
        int totalTokens = 0;
        for (int i = 0; i < sampleCount; i++) {
            JudgeResult result = judgeSingle(actualOutput, expectedPattern, criteria);
            samples.add(result);
            totalTokens += result.tokensUsed();
        }

        return averageSamples(samples, totalTokens);
    }

    /**
     * 单次 LLM 语义评估（三级降级策略）。
     */
    private JudgeResult judgeSingle(String actualOutput, String expectedPattern, String criteria) {
        var judgeConfig = config.getLlmJudge();
        var scene = judgeConfig.getScene();
        var fallbackScore = judgeConfig.getFallbackScore();

        try {
            var prompt = buildPrompt(actualOutput, expectedPattern, criteria);

            // 第一级：callEntity 结构化解析
            try {
                JudgeResponse response = generationRouter.callEntity(
                        scene,
                        prompt,
                        JudgeResponse.class,
                        null,
                        null,
                        null);

                if (response != null && response.overallScore() != null) {
                    var score = clampScore(response.overallScore());
                    var justification = response.justification() != null
                            ? response.justification() : "无评判理由";
                    var dimensions = parseDimensions(response.dimensions());
                    var suggestions = response.suggestions() != null
                            ? response.suggestions() : List.<String>of();

                    log.debug("LLM Judge 评估完成（callEntity）: score={}", score);
                    return new JudgeResult(score, justification, 0, false, dimensions, suggestions);
                }

                log.warn("LLM Judge callEntity 返回空结果，降级到手动解析");
                return fallbackToManualParse(actualOutput, expectedPattern, criteria);

            } catch (Exception e) {
                log.warn("LLM Judge callEntity 失败，降级到手动解析: error={}", e.getMessage());
                return fallbackToManualParse(actualOutput, expectedPattern, criteria);
            }

        } catch (Exception e) {
            log.warn("LLM Judge 调用失败: error={}", e.getMessage());
            return new JudgeResult(fallbackScore, "LLM 调用失败: " + e.getMessage(), 0, true);
        }
    }

    /**
     * 降级到手动解析（第二级）。
     */
    private JudgeResult fallbackToManualParse(String actualOutput, String expectedPattern, String criteria) {
        var judgeConfig = config.getLlmJudge();
        var scene = judgeConfig.getScene();
        var fallbackScore = judgeConfig.getFallbackScore();

        try {
            var prompt = buildPrompt(actualOutput, expectedPattern, criteria);
            var response = generationRouter.call(
                    scene,
                    prompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    null);
            var tokensUsed = response.totalTokens();

            var parsed = parseResponse(response.content());
            if (parsed != null) {
                var score = clampScore(parsed.score());
                log.debug("LLM Judge 手动解析成功: score={}, tokens={}", score, tokensUsed);
                return new JudgeResult(score, parsed.justification(), tokensUsed, false,
                        parsed.dimensions(), parsed.suggestions());
            }

            // 第三级：简化 Prompt 重试
            log.warn("LLM Judge 响应解析失败，使用简化 Prompt 重试");
            return retryWithSimplifiedPrompt(actualOutput, expectedPattern, criteria, tokensUsed);

        } catch (Exception e) {
            log.warn("LLM Judge 手动解析调用失败: error={}", e.getMessage());
            return new JudgeResult(fallbackScore, "LLM 调用失败: " + e.getMessage(), 0, true);
        }
    }

    /**
     * 使用简化 Prompt 重试（第三级）。
     */
    private JudgeResult retryWithSimplifiedPrompt(String actualOutput, String expectedPattern,
                                                   String criteria, int previousTokens) {
        var judgeConfig = config.getLlmJudge();
        var scene = judgeConfig.getScene();
        var fallbackScore = judgeConfig.getFallbackScore();

        try {
            var simplifiedPrompt = buildSimplifiedPrompt(actualOutput, expectedPattern, criteria);
            var response = generationRouter.call(
                    scene,
                    simplifiedPrompt,
                    null,
                    null,
                    null,
                    GenerationCapability.CHAT,
                    null);
            var totalTokens = previousTokens + response.totalTokens();

            var score = parseScoreFromText(response.content());
            if (score != null) {
                var clamped = clampScore(score);
                log.debug("LLM Judge 简化重试成功: score={}, tokens={}", clamped, totalTokens);
                return new JudgeResult(clamped, "简化 Prompt 重试评估", totalTokens, false);
            }

            log.warn("LLM Judge 简化重试仍无法解析评分，使用降级评分");
            return new JudgeResult(fallbackScore, "LLM 响应解析失败，使用降级评分", totalTokens, true);

        } catch (Exception e) {
            log.warn("LLM Judge 简化重试调用失败: error={}", e.getMessage());
            return new JudgeResult(fallbackScore, "LLM 调用失败: " + e.getMessage(), previousTokens, true);
        }
    }

    /**
     * 多次采样结果取平均。
     */
    private JudgeResult averageSamples(List<JudgeResult> samples, int totalTokens) {
        double avgScore = samples.stream().mapToDouble(JudgeResult::score).average().orElse(0.5);
        boolean anyFallback = samples.stream().anyMatch(JudgeResult::fallback);

        // 合并子维度评分（取平均）
        Map<String, Double> avgDimensions = new HashMap<>();
        for (JudgeResult sample : samples) {
            for (var entry : sample.dimensionScores().entrySet()) {
                avgDimensions.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }
        int validSamples = samples.size();
        avgDimensions.replaceAll((k, v) -> v / validSamples);

        // 合并建议（去重）
        List<String> allSuggestions = samples.stream()
                .flatMap(s -> s.suggestions().stream())
                .distinct()
                .toList();

        // 使用第一个非降级结果的 justification
        String justification = samples.stream()
                .filter(s -> !s.fallback())
                .map(JudgeResult::justification)
                .findFirst()
                .orElse("多次采样平均评分（%d 次）".formatted(validSamples));

        log.debug("LLM Judge 多次采样完成: samples={}, avgScore={}, tokens={}",
                validSamples, avgScore, totalTokens);

        return new JudgeResult(clampScore(avgScore), justification, totalTokens,
                anyFallback, avgDimensions, allSuggestions);
    }

    /**
     * 构造完整评估 Prompt。
     */
    private String buildPrompt(String actualOutput, String expectedPattern, String criteria) {
        return promptRegistry.render("eval/judge-full", Map.of(
                "criteria", criteria,
                "expectedPattern", expectedPattern,
                "actualOutput", actualOutput));
    }

    /**
     * 构造简化 Prompt（仅要求返回数字评分）。
     */
    private String buildSimplifiedPrompt(String actualOutput, String expectedPattern, String criteria) {
        return promptRegistry.render("eval/judge-simplified", Map.of(
                "criteria", criteria,
                "expectedPattern", expectedPattern,
                "actualOutput", actualOutput));
    }

    /**
     * 解析 LLM 响应为结构化结果（手动 JSON 解析降级方案）。
     */
    private ParsedResponse parseResponse(String content) {
        var score = parseScoreFromText(content);
        if (score != null) {
            Map<String, Double> dimensions = Map.of();
            List<String> suggestions = List.of();
            try {
                dimensions = extractDimensions(content);
                suggestions = extractSuggestions(content);
            } catch (Exception ignored) {
                // 降级：只返回总分
            }
            return new ParsedResponse(score, content.trim(), dimensions, suggestions);
        }
        return null;
    }

    /**
     * 从文本中提取子维度评分。
     */
    private Map<String, Double> extractDimensions(String content) {
        Map<String, Double> dims = new HashMap<>();
        String[] keys = {"accuracy", "completeness", "safety", "style"};
        for (String key : keys) {
            Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+\\.?\\d*)");
            Matcher m = p.matcher(content);
            if (m.find()) {
                try {
                    dims.put(key, clampScore(Double.parseDouble(m.group(1))));
                } catch (NumberFormatException ignored) {}
            }
        }
        return dims;
    }

    /**
     * 从文本中提取建议列表。
     */
    private List<String> extractSuggestions(String content) {
        List<String> suggestions = new ArrayList<>();
        Pattern p = Pattern.compile("\"suggestions\"\\s*:\\s*\\[([^\\]]*)]");
        Matcher m = p.matcher(content);
        if (m.find()) {
            String arrayContent = m.group(1);
            Pattern itemP = Pattern.compile("\"([^\"]+)\"");
            Matcher itemM = itemP.matcher(arrayContent);
            while (itemM.find()) {
                suggestions.add(itemM.group(1));
            }
        }
        return suggestions;
    }

    /**
     * 从文本中用正则提取评分（优先匹配 overallScore，其次 score，最后任意数字）。
     */
    private Double parseScoreFromText(String content) {
        // 优先匹配 overallScore 字段
        Pattern overallP = Pattern.compile("\"overallScore\"\\s*:\\s*(\\d+\\.?\\d*)");
        Matcher overallM = overallP.matcher(content);
        if (overallM.find()) {
            try {
                return Double.parseDouble(overallM.group(1));
            } catch (NumberFormatException ignored) {}
        }

        // 回退到匹配 score 字段
        Pattern scoreP = Pattern.compile("\"score\"\\s*:\\s*(\\d+\\.?\\d*)");
        Matcher scoreM = scoreP.matcher(content);
        if (scoreM.find()) {
            try {
                return Double.parseDouble(scoreM.group(1));
            } catch (NumberFormatException ignored) {}
        }

        // 最后回退到匹配任意数字（需范围校验 [0.0, 1.0]）
        Matcher matcher = SCORE_PATTERN.matcher(content.trim());
        if (matcher.find()) {
            try {
                double val = Double.parseDouble(matcher.group(1));
                if (val >= 0.0 && val <= 1.0) {
                    return val;
                }
                // 超出 [0, 1] 范围的数字不是评分，返回 null
                return null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 将 JudgeResponse.dimensions 转换为 Map。
     */
    private Map<String, Double> parseDimensions(@Nullable DimensionScores dims) {
        if (dims == null) return Map.of();
        var map = new HashMap<String, Double>();
        if (dims.accuracy() != null) map.put("accuracy", clampScore(dims.accuracy()));
        if (dims.completeness() != null) map.put("completeness", clampScore(dims.completeness()));
        if (dims.safety() != null) map.put("safety", clampScore(dims.safety()));
        if (dims.style() != null) map.put("style", clampScore(dims.style()));
        return Map.copyOf(map);
    }

    /**
     * 将评分裁剪到 [0.0, 1.0] 范围。
     */
    private double clampScore(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * LLM 评判响应结构（用于 callEntity 解析）。
     */
    public record JudgeResponse(
            @JsonProperty("overallScore") @Nullable Double overallScore,
            @JsonProperty("justification") @Nullable String justification,
            @JsonProperty("dimensions") @Nullable DimensionScores dimensions,
            @JsonProperty("suggestions") @Nullable List<String> suggestions
    ) {}

    /**
     * 子维度评分结构。
     */
    public record DimensionScores(
            @JsonProperty("accuracy") @Nullable Double accuracy,
            @JsonProperty("completeness") @Nullable Double completeness,
            @JsonProperty("safety") @Nullable Double safety,
            @JsonProperty("style") @Nullable Double style
    ) {}

    /**
     * 内部解析结果（降级方案使用）。
     */
    private record ParsedResponse(double score, String justification,
                                   Map<String, Double> dimensions, List<String> suggestions) {}
}
