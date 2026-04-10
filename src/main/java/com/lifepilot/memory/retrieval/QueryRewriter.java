package com.lifepilot.memory.retrieval;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆查询改写器，通过生成式改写或 HyDE 提升记忆召回质量。
 *
 * <p>执行顺序位于 {@link QueryRefiner} 之后，调用失败时自动降级回原始精炼查询。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
    private static final String SCENE = LlmScene.MEMORY_COMPRESSION;

    private final GenerationRouter generationRouter;
    private final EmbeddingRouter embeddingRouter;
    private final MemoryProperties.Retrieval retrievalConfig;
    private final PromptRegistry promptRegistry;

    public QueryRewriter(GenerationRouter generationRouter,
                         EmbeddingRouter embeddingRouter,
                         MemoryProperties properties,
                         PromptRegistry promptRegistry) {
        this.generationRouter = generationRouter;
        this.embeddingRouter = embeddingRouter;
        this.retrievalConfig = properties.getRetrieval();
        this.promptRegistry = promptRegistry;
        log.debug("初始化 QueryRewriter: mode={}, timeoutMs={}, maxRewrites={}",
                retrievalConfig.getQueryRewriteMode(),
                retrievalConfig.getRewriteTimeoutMs(),
                retrievalConfig.getMaxRewrites());
    }

    public RewriteResult rewrite(String refinedQuery) {
        long start = System.currentTimeMillis();
        try {
            RewriteResult result = switch (retrievalConfig.getQueryRewriteMode()) {
                case "rewrite" -> rewriteQuery(refinedQuery);
                case "hyde" -> hydeQuery(refinedQuery);
                default -> new RewriteResult(refinedQuery, List.of(), Optional.empty());
            };
            long elapsed = System.currentTimeMillis() - start;
            log.debug("查询改写完成: mode={}, refinedQuery={}, rewrites={}, elapsedMs={}",
                    retrievalConfig.getQueryRewriteMode(), refinedQuery,
                    result.rewrittenQueries().size(), elapsed);
            return result;
        } catch (LlmUnavailableException e) {
            log.warn("查询改写不可用，降级返回原始查询: {}", e.getMessage());
            return new RewriteResult(refinedQuery, List.of(), Optional.empty());
        } catch (Exception e) {
            log.warn("查询改写失败，降级返回原始查询: {}", e.getMessage());
            return new RewriteResult(refinedQuery, List.of(), Optional.empty());
        }
    }

    private RewriteResult rewriteQuery(String refinedQuery) {
        String prompt = promptRegistry.render("memory/query-rewrite", Map.of(
                "maxRewrites", String.valueOf(retrievalConfig.getMaxRewrites()),
                "originalQuery", refinedQuery));

        String responseText = callWithTimeout(prompt);
        List<String> rewrites = parseJsonArray(responseText);
        if (rewrites.isEmpty()) {
            return new RewriteResult(refinedQuery, List.of(), Optional.empty());
        }
        if (rewrites.size() > retrievalConfig.getMaxRewrites()) {
            rewrites = rewrites.subList(0, retrievalConfig.getMaxRewrites());
        }
        return new RewriteResult(refinedQuery, List.copyOf(rewrites), Optional.empty());
    }

    private RewriteResult hydeQuery(String refinedQuery) {
        String prompt = promptRegistry.render("memory/hyde-generation", Map.of(
                "originalQuery", refinedQuery));
        String hypotheticalDoc = callWithTimeout(prompt);
        float[] embedding = embeddingRouter.embed(hypotheticalDoc, EmbeddingUseCase.MEMORY, null, null);
        return new RewriteResult(refinedQuery, List.of(), Optional.of(embedding));
    }

    private String callWithTimeout(String prompt) {
        return generationRouter.call(
                SCENE,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                Duration.ofMillis(retrievalConfig.getRewriteTimeoutMs())
        ).content();
    }

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
                    String value = current.toString().trim();
                    if (!value.isEmpty()) {
                        results.add(value);
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

    public record RewriteResult(
            String primaryQuery,
            List<String> rewrittenQueries,
            Optional<float[]> hydeEmbedding
    ) {}
}
