package com.lifepilot.knowledge.retrieve;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 查询增强器 — 通过 LLM 改写或扩展用户查询以提升检索精度。
 *
 * <p>支持三种模式：
 * <ul>
 *   <li>{@code rewrite}：调用 LLM 生成查询改写变体</li>
 *   <li>{@code hyde}：生成假设性文档片段并获取 Embedding</li>
 *   <li>{@code none}：不增强，直接返回原始查询</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-06
 */
public class QueryEnhancer {

    private static final Logger log = LoggerFactory.getLogger(QueryEnhancer.class);
    private static final String SCENE = "query_enhance";

    private final LlmRouter llmRouter;
    private final KnowledgeBaseProperties.QueryEnhancer config;

    /**
     * 构造查询增强器。
     *
     * @param llmRouter LLM 路由器
     * @param config    查询增强配置
     */
    public QueryEnhancer(LlmRouter llmRouter, KnowledgeBaseProperties.QueryEnhancer config) {
        this.llmRouter = llmRouter;
        this.config = config;
        log.debug("初始化 QueryEnhancer: mode={}, timeoutMs={}, maxRewrites={}",
                config.mode(), config.timeoutMs(), config.maxRewrites());
    }

    /**
     * 增强查询。
     *
     * @param originalQuery 原始查询
     * @return 增强结果
     */
    public EnhancedQuery enhance(String originalQuery) {
        long start = System.currentTimeMillis();
        try {
            var result = switch (config.mode()) {
                case "rewrite" -> rewriteQuery(originalQuery);
                case "hyde" -> hydeQuery(originalQuery);
                default -> new EnhancedQuery(originalQuery, List.of(), Optional.empty());
            };
            long elapsed = System.currentTimeMillis() - start;
            log.debug("查询增强完成: mode={}, 原始查询='{}', 改写数={}, 耗时={}ms",
                    config.mode(), originalQuery, result.rewrittenQueries().size(), elapsed);
            return result;
        } catch (LlmUnavailableException e) {
            log.warn("查询增强 LLM 不可用，降级返回原始查询: {}", e.getMessage());
            return new EnhancedQuery(originalQuery, List.of(), Optional.empty());
        } catch (Exception e) {
            log.warn("查询增强失败，降级返回原始查询: {}", e.getMessage());
            return new EnhancedQuery(originalQuery, List.of(), Optional.empty());
        }
    }

    /**
     * 查询改写：调用 LLM 生成改写变体。
     */
    private EnhancedQuery rewriteQuery(String originalQuery) {
        var prompt = """
                请将以下用户查询改写为 %d 个不同的搜索查询变体，保持原始语义但使用不同的表达方式。
                返回 JSON 数组格式：["改写1", "改写2", ...]
                
                用户查询：%s
                
                请直接输出 JSON 数组，不要包含任何解释。""".formatted(config.maxRewrites(), originalQuery);

        String responseText = callWithTimeout(prompt);
        List<String> rewrites = parseJsonArray(responseText);

        // 确保数量在范围内
        if (rewrites.isEmpty()) {
            return new EnhancedQuery(originalQuery, List.of(), Optional.empty());
        }
        if (rewrites.size() > config.maxRewrites()) {
            rewrites = rewrites.subList(0, config.maxRewrites());
        }

        return new EnhancedQuery(originalQuery, List.copyOf(rewrites), Optional.empty());
    }

    /**
     * HyDE：生成假设性文档片段并获取 Embedding。
     */
    private EnhancedQuery hydeQuery(String originalQuery) {
        var prompt = """
                请根据以下查询，撰写一段 200-300 字的假设性文档片段，\
                该片段应该是回答该查询的理想文档内容。
                
                查询：%s
                
                请直接输出文档片段，不要包含任何前缀或解释。""".formatted(originalQuery);

        String hypotheticalDoc = callWithTimeout(prompt);
        float[] embedding = llmRouter.embed(hypotheticalDoc);

        return new EnhancedQuery(originalQuery, List.of(), Optional.of(embedding));
    }

    /**
     * 带超时的 LLM 调用。
     */
    private String callWithTimeout(String prompt) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> future = executor.submit(
                    () -> llmRouter.call(SCENE, prompt, null).content());
            return future.get(config.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("查询增强超时: timeoutMs={}", config.timeoutMs());
            throw new RuntimeException("查询增强超时", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LlmUnavailableException ue) {
                throw ue;
            }
            throw new RuntimeException("查询增强执行失败", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("查询增强被中断", e);
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
     * 增强查询结果。
     *
     * @param primaryQuery     原始查询
     * @param rewrittenQueries 改写后的查询列表
     * @param hydeEmbedding    HyDE 生成的假设文档 Embedding
     */
    public record EnhancedQuery(
            String primaryQuery,
            List<String> rewrittenQueries,
            Optional<float[]> hydeEmbedding
    ) {}
}
