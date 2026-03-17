package com.lifepilot.memory.retrieval;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 记忆查询改写器 — 通过 LLM 改写或扩展用户查询以提升记忆检索召回。
 *
 * <p>支持三种模式：
 * <ul>
 *   <li>{@code rewrite}：调用 LLM 生成查询改写变体</li>
 *   <li>{@code hyde}：生成假设性文档片段并获取 Embedding</li>
 *   <li>{@code none}：不改写，直接返回原始查询</li>
 * </ul>
 *
 * <p>在 {@link QueryRefiner} 文本清洗之后执行。LLM 调用失败/超时时降级返回原始查询。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
    private static final String SCENE = "memory_query_rewrite";

    private final LlmRouter llmRouter;
    private final MemoryProperties.Retrieval retrievalConfig;
    private final PromptRegistry promptRegistry;

    /**
     * 构造记忆查询改写器。
     *
     * @param llmRouter      LLM 路由器
     * @param properties     记忆系统配置
     * @param promptRegistry 提示词模板注册表
     */
    public QueryRewriter(LlmRouter llmRouter, MemoryProperties properties,
                         PromptRegistry promptRegistry) {
        this.llmRouter = llmRouter;
        this.retrievalConfig = properties.getRetrieval();
        this.promptRegistry = promptRegistry;
        log.debug("初始化 QueryRewriter: mode={}, timeoutMs={}, maxRewrites={}",
                retrievalConfig.getQueryRewriteMode(),
                retrievalConfig.getRewriteTimeoutMs(),
                retrievalConfig.getMaxRewrites());
    }

    /**
     * 改写查询。
     *
     * @param refinedQuery QueryRefiner 精炼后的查询文本
     * @return 改写结果
     */
    public RewriteResult rewrite(String refinedQuery) {
        long start = System.currentTimeMillis();
        try {
            var result = switch (retrievalConfig.getQueryRewriteMode()) {
                case "rewrite" -> rewriteQuery(refinedQuery);
                case "hyde" -> hydeQuery(refinedQuery);
                default -> new RewriteResult(refinedQuery, List.of(), Optional.empty());
            };
            long elapsed = System.currentTimeMillis() - start;
            log.debug("查询改写完成: mode={}, 原始查询='{}', 改写数={}, 耗时={}ms",
                    retrievalConfig.getQueryRewriteMode(), refinedQuery,
                    result.rewrittenQueries().size(), elapsed);
            return result;
        } catch (LlmUnavailableException e) {
            log.warn("查询改写 LLM 不可用，降级返回原始查询: {}", e.getMessage());
            return new RewriteResult(refinedQuery, List.of(), Optional.empty());
        } catch (Exception e) {
            log.warn("查询改写失败，降级返回原始查询: {}", e.getMessage());
            return new RewriteResult(refinedQuery, List.of(), Optional.empty());
        }
    }

    /**
     * 查询改写：调用 LLM 生成改写变体。
     */
    private RewriteResult rewriteQuery(String refinedQuery) {
        var prompt = promptRegistry.render("memory/query-rewrite", Map.of(
                "maxRewrites", String.valueOf(retrievalConfig.getMaxRewrites()),
                "originalQuery", refinedQuery));

        String responseText = callWithTimeout(prompt);
        List<String> rewrites = parseJsonArray(responseText);

        // 确保数量在范围内
        if (rewrites.isEmpty()) {
            return new RewriteResult(refinedQuery, List.of(), Optional.empty());
        }
        if (rewrites.size() > retrievalConfig.getMaxRewrites()) {
            rewrites = rewrites.subList(0, retrievalConfig.getMaxRewrites());
        }

        return new RewriteResult(refinedQuery, List.copyOf(rewrites), Optional.empty());
    }

    /**
     * HyDE：生成假设性文档片段并获取 Embedding。
     */
    private RewriteResult hydeQuery(String refinedQuery) {
        var prompt = promptRegistry.render("memory/hyde-generation", Map.of(
                "originalQuery", refinedQuery));

        String hypotheticalDoc = callWithTimeout(prompt);
        float[] embedding = llmRouter.embed(hypotheticalDoc);

        return new RewriteResult(refinedQuery, List.of(), Optional.of(embedding));
    }

    /**
     * 带超时的 LLM 调用。
     */
    private String callWithTimeout(String prompt) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> future = executor.submit(
                    () -> llmRouter.call(LlmRequest.of(SCENE, prompt)).content());
            return future.get(retrievalConfig.getRewriteTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("查询改写超时: timeoutMs={}", retrievalConfig.getRewriteTimeoutMs());
            throw new RuntimeException("查询改写超时", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LlmUnavailableException ue) {
                throw ue;
            }
            throw new RuntimeException("查询改写执行失败", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("查询改写被中断", e);
        }
    }

    /**
     * 解析 JSON 数组字符串。
     */
    private List<String> parseJsonArray(String response) {
        String trimmed = response.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }

        String arrayContent = trimmed.substring(start + 1, end);
        var results = new java.util.ArrayList<String>();
        boolean inString = false;
        boolean escaped = false;
        var current = new StringBuilder();

        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (inString) {
                    String val = current.toString().trim();
                    if (!val.isEmpty()) {
                        results.add(val);
                    }
                    current = new StringBuilder();
                }
                inString = !inString;
                continue;
            }
            if (inString) {
                current.append(c);
            }
        }
        return results;
    }

    /**
     * 改写结果。
     *
     * @param primaryQuery     原始精炼查询
     * @param rewrittenQueries 改写变体列表（rewrite 模式）
     * @param hydeEmbedding    假设文档 embedding（hyde 模式）
     */
    public record RewriteResult(
            String primaryQuery,
            List<String> rewrittenQueries,
            Optional<float[]> hydeEmbedding
    ) {}
}
