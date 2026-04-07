package com.lifepilot.knowledge.retrieve;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 检索质量评估器 — Corrective RAG 核心组件。
 *
 * <p>在检索完成后评估结果与查询的相关性，支持三级判定：
 * <ul>
 *   <li>{@code HIGH}：至少有一条结果直接回答了查询</li>
 *   <li>{@code LOW}：结果相关但不够直接，改写查询可能获得更好结果</li>
 *   <li>{@code VERY_LOW}：所有结果均不相关</li>
 * </ul>
 *
 * <p>当 Top-1 分数高于 {@code highThreshold} 时直接返回 HIGH，跳过 LLM 调用。
 * LLM 调用失败时降级为 HIGH（fail-open 策略），不阻塞主检索流程。
 *
 * @author zsg
 * @since 2026-04-07
 */
public class RetrievalQualityEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RetrievalQualityEvaluator.class);
    private static final String SCENE = "retrieval_quality_eval";
    /** 每个片段截取的最大字符数 */
    private static final int MAX_CONTENT_CHARS = 500;
    /** 送入评估的最大片段数 */
    private static final int MAX_EVAL_RESULTS = 3;

    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;
    private final double highThreshold;
    private final int timeoutMs;

    /**
     * 检索质量等级。
     */
    public enum Quality { HIGH, LOW, VERY_LOW }

    /**
     * 评估结果。
     *
     * @param quality          质量等级
     * @param reason           判定原因
     * @param suggestedRewrite 建议的改写查询（仅 LOW 时有值）
     */
    public record EvaluationResult(Quality quality, String reason, Optional<String> suggestedRewrite) {}

    /**
     * 构造检索质量评估器。
     *
     * @param generationRouter 生成路由器
     * @param promptRegistry   提示词注册器
     * @param highThreshold    Top-1 分数高于此阈值时跳过 LLM 评估
     * @param timeoutMs        LLM 调用超时毫秒数
     */
    public RetrievalQualityEvaluator(GenerationRouter generationRouter,
                                     PromptRegistry promptRegistry,
                                     double highThreshold,
                                     int timeoutMs) {
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.highThreshold = highThreshold;
        this.timeoutMs = timeoutMs;
        log.info("RetrievalQualityEvaluator 初始化完成: highThreshold={}, timeoutMs={}", highThreshold, timeoutMs);
    }

    /**
     * 评估检索结果与查询的相关性。
     *
     * @param query   用户查询
     * @param results 检索结果列表（已按相关性降序排列）
     * @return 评估结果
     */
    public EvaluationResult evaluate(String query, List<DocumentSearchResult> results) {
        // 快速路径：Top-1 分数高于阈值，直接返回 HIGH
        if (!results.isEmpty() && results.getFirst().score() >= highThreshold) {
            return new EvaluationResult(Quality.HIGH, "Top-1 分数高于阈值", Optional.empty());
        }

        // 构建评估 Prompt，取 top-3 片段，每个截取前 500 字符
        var resultsText = new StringBuilder();
        for (int i = 0; i < Math.min(MAX_EVAL_RESULTS, results.size()); i++) {
            var r = results.get(i);
            String content = r.content().length() > MAX_CONTENT_CHARS
                    ? r.content().substring(0, MAX_CONTENT_CHARS) + "..."
                    : r.content();
            resultsText.append("片段 ").append(i + 1)
                    .append("（分数 %.2f）：\n".formatted(r.score()))
                    .append(content).append("\n\n");
        }

        String prompt = promptRegistry.render("knowledge/retrieval-quality-eval", Map.of(
                "query", query,
                "results", resultsText.toString()));

        // 调用 LLM 结构化输出
        try {
            var response = generationRouter.callEntity(
                    SCENE, prompt, EvalResponse.class,
                    null, null, Duration.ofMillis(timeoutMs));

            Quality quality = switch (response.quality().toUpperCase()) {
                case "HIGH" -> Quality.HIGH;
                case "LOW" -> Quality.LOW;
                default -> Quality.VERY_LOW;
            };
            return new EvaluationResult(quality, response.reason(),
                    Optional.ofNullable(response.suggestedRewrite()));
        } catch (Exception e) {
            // Fail-open：LLM 不可用时降级为 HIGH，不阻塞检索
            log.warn("检索质量评估失败，降级为 HIGH: {}", e.getMessage());
            return new EvaluationResult(Quality.HIGH, "评估不可用，降级通过", Optional.empty());
        }
    }

    /**
     * LLM 结构化输出响应。
     */
    private record EvalResponse(String quality, String reason, String suggestedRewrite) {}
}
