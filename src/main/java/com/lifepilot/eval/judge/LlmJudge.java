package com.lifepilot.eval.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.eval.config.EvalConfigProperties;
import com.lifepilot.llm.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 评判器 — 使用 LLM 评估语义维度。
 *
 * <p>构造评估 Prompt 发送给 LLM，解析响应为结构化评分和理由。
 * LLM 调用失败或响应无法解析时自动降级。</p>
 *
 * @author zsg
 * @since 2026-08-01
 */
public class LlmJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmJudge.class);

    /** 匹配 0.0 ~ 1.0 范围内的小数（含整数 0 和 1）。 */
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+\\.?\\d*)");

    private final LlmRouter llmRouter;
    private final EvalConfigProperties config;
    private final ObjectMapper objectMapper;

    public LlmJudge(LlmRouter llmRouter, EvalConfigProperties config) {
        this.llmRouter = llmRouter;
        this.config = config;
        this.objectMapper = new ObjectMapper();
        log.info("LlmJudge 初始化完成: scene={}", config.getLlmJudge().getScene());
    }

    /**
     * LLM 语义评估。
     *
     * @param actualOutput    Agent 实际输出
     * @param expectedPattern 期望输出模式
     * @param criteria        评估标准
     * @return 评判结果（评分 + 理由）
     */
    public JudgeResult judge(String actualOutput, String expectedPattern, String criteria) {
        var judgeConfig = config.getLlmJudge();
        var scene = judgeConfig.getScene();
        var fallbackScore = judgeConfig.getFallbackScore();

        try {
            // 第一次尝试：完整 Prompt
            var prompt = buildPrompt(actualOutput, expectedPattern, criteria);
            var response = llmRouter.call(scene, prompt, null);
            var tokensUsed = response.totalTokens();

            var parsed = parseResponse(response.content());
            if (parsed != null) {
                var score = clampScore(parsed.score());
                log.debug("LLM Judge 评估完成: score={}, tokens={}", score, tokensUsed);
                return new JudgeResult(score, parsed.justification(), tokensUsed, false);
            }

            // 解析失败，使用简化 Prompt 重试
            log.warn("LLM Judge 响应解析失败，使用简化 Prompt 重试");
            return retryWithSimplifiedPrompt(actualOutput, expectedPattern, criteria, tokensUsed);

        } catch (Exception e) {
            log.warn("LLM Judge 调用失败: error={}", e.getMessage());
            return new JudgeResult(fallbackScore, "LLM 调用失败: " + e.getMessage(), 0, true);
        }
    }

    /**
     * 使用简化 Prompt 重试一次，仍失败则返回降级结果。
     */
    private JudgeResult retryWithSimplifiedPrompt(String actualOutput, String expectedPattern,
                                                   String criteria, int previousTokens) {
        var judgeConfig = config.getLlmJudge();
        var scene = judgeConfig.getScene();
        var fallbackScore = judgeConfig.getFallbackScore();

        try {
            var simplifiedPrompt = buildSimplifiedPrompt(actualOutput, expectedPattern, criteria);
            var response = llmRouter.call(scene, simplifiedPrompt, null);
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
     * 构造完整评估 Prompt。
     */
    private String buildPrompt(String actualOutput, String expectedPattern, String criteria) {
        return """
                你是一个 AI 输出质量评判专家。请根据以下信息评估 Agent 的输出质量。
                
                ## 评估标准
                %s
                
                ## 期望输出模式
                %s
                
                ## Agent 实际输出
                %s
                
                ## 要求
                请以 JSON 格式返回评估结果，包含以下字段：
                - "score": 评分，范围 0.0 到 1.0（0.0 表示完全不符合，1.0 表示完全符合）
                - "justification": 评判理由，简要说明评分依据
                
                示例：
                {"score": 0.85, "justification": "输出包含了关键信息，但缺少部分细节"}
                """.formatted(criteria, expectedPattern, actualOutput);
    }

    /**
     * 构造简化 Prompt（仅要求返回数字评分）。
     */
    private String buildSimplifiedPrompt(String actualOutput, String expectedPattern, String criteria) {
        return """
                请评估以下 Agent 输出的质量，只返回一个 0.0 到 1.0 之间的数字评分。
                
                评估标准：%s
                期望模式：%s
                实际输出：%s
                
                请只返回一个数字（如 0.75），不要返回其他内容。
                """.formatted(criteria, expectedPattern, actualOutput);
    }

    /**
     * 解析 LLM 响应为评分和理由。
     *
     * @param content LLM 响应内容
     * @return 解析结果，解析失败返回 null
     */
    private ParsedResponse parseResponse(String content) {
        // 尝试 JSON 解析
        try {
            var jsonResult = parseAsJson(content);
            if (jsonResult != null) {
                return jsonResult;
            }
        } catch (Exception e) {
            log.debug("JSON 解析失败，尝试正则提取: error={}", e.getMessage());
        }

        // 尝试正则提取评分
        var score = parseScoreFromText(content);
        if (score != null) {
            return new ParsedResponse(score, content.trim());
        }

        return null;
    }

    /**
     * 尝试将响应内容解析为 JSON，提取 score 和 justification。
     */
    private ParsedResponse parseAsJson(String content) {
        try {
            // 提取 JSON 块（可能被 markdown 代码块包裹）
            var jsonStr = extractJsonBlock(content);
            var node = objectMapper.readTree(jsonStr);

            if (node.has("score")) {
                var score = node.get("score").asDouble();
                var justification = node.has("justification")
                        ? node.get("justification").asText()
                        : "无评判理由";
                return new ParsedResponse(score, justification);
            }
        } catch (Exception ignored) {
            // JSON 解析失败，返回 null
        }
        return null;
    }

    /**
     * 从文本中提取 JSON 块（处理 markdown 代码块包裹的情况）。
     */
    private String extractJsonBlock(String content) {
        var trimmed = content.trim();
        // 处理 ```json ... ``` 包裹
        if (trimmed.contains("```")) {
            int start = trimmed.indexOf("{");
            int end = trimmed.lastIndexOf("}");
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        // 直接尝试提取 JSON 对象
        int start = trimmed.indexOf("{");
        int end = trimmed.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    /**
     * 从文本中用正则提取第一个数字作为评分。
     *
     * @return 评分，提取失败返回 null
     */
    private Double parseScoreFromText(String content) {
        Matcher matcher = SCORE_PATTERN.matcher(content.trim());
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 将评分裁剪到 [0.0, 1.0] 范围。
     */
    private double clampScore(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }

    /**
     * 内部解析结果。
     */
    private record ParsedResponse(double score, String justification) {
    }
}
