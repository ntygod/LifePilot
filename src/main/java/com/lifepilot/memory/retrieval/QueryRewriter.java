package com.lifepilot.memory.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 记忆查询改写器，通过生成式改写或 HyDE 提升记忆召回质量。
 *
 * <p>执行顺序位于 {@link QueryRefiner} 之后。启用改写模式时，模型或嵌入调用失败会直接暴露。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);
    private static final String SCENE = LlmScene.MEMORY_COMPRESSION;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GenerationRouter generationRouter;
    private final EmbeddingRouter embeddingRouter;
    private final MemoryRetrievalProperties retrievalConfig;
    private final PromptRegistry promptRegistry;

    public QueryRewriter(GenerationRouter generationRouter,
                         EmbeddingRouter embeddingRouter,
                         MemoryRetrievalProperties properties,
                         PromptRegistry promptRegistry) {
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter");
        this.embeddingRouter = Objects.requireNonNull(embeddingRouter, "embeddingRouter");
        this.retrievalConfig = Objects.requireNonNull(properties, "properties");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry");
        log.debug("初始化 QueryRewriter: mode={}, timeoutMs={}, maxRewrites={}",
                retrievalConfig.getQueryRewriteMode(),
                retrievalConfig.getRewriteTimeoutMs(),
                retrievalConfig.getMaxRewrites());
    }

    public RewriteResult rewrite(String refinedQuery) {
        long start = System.currentTimeMillis();
        RewriteResult result = switch (retrievalConfig.getQueryRewriteMode()) {
            case "rewrite" -> rewriteQuery(refinedQuery);
            case "hyde" -> hydeQuery(refinedQuery);
            case "none" -> new RewriteResult(refinedQuery, List.of(), Optional.empty());
            default -> throw new IllegalStateException(
                    "未知查询改写模式: " + retrievalConfig.getQueryRewriteMode());
        };
        long elapsed = System.currentTimeMillis() - start;
        log.debug("查询改写完成: mode={}, refinedQuery={}, rewrites={}, elapsedMs={}",
                retrievalConfig.getQueryRewriteMode(), refinedQuery,
                result.rewrittenQueries().size(), elapsed);
        return result;
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
            throw new IllegalStateException("查询改写数组解析失败: 改写数量超过上限 "
                    + retrievalConfig.getMaxRewrites());
        }
        return new RewriteResult(refinedQuery, List.copyOf(rewrites), Optional.empty());
    }

    private RewriteResult hydeQuery(String refinedQuery) {
        String prompt = promptRegistry.render("memory/hyde-generation", Map.of(
                "originalQuery", refinedQuery));
        String hypotheticalDoc = callWithTimeout(prompt);
        if (hypotheticalDoc == null || hypotheticalDoc.isBlank()) {
            throw new IllegalStateException("HyDE 生成返回空内容");
        }
        float[] embedding = embeddingRouter.embed(hypotheticalDoc, EmbeddingUseCase.MEMORY, null, null);
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("HyDE 嵌入返回空向量");
        }
        return new RewriteResult(refinedQuery, List.of(), Optional.of(embedding));
    }

    private String callWithTimeout(String prompt) {
        LlmResponse response = generationRouter.call(
                SCENE,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                Duration.ofMillis(retrievalConfig.getRewriteTimeoutMs())
        );
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("查询改写 LLM 返回空内容");
        }
        return response.content();
    }

    private List<String> parseJsonArray(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("查询改写数组解析失败: LLM 返回空内容");
        }
        try {
            JsonNode root = MAPPER.readTree(response);
            if (!root.isArray()) {
                throw new IllegalStateException("查询改写数组解析失败: 顶层必须是 JSON 数组");
            }
            var rewrites = new java.util.ArrayList<String>();
            for (JsonNode item : root) {
                if (!item.isTextual()) {
                    throw new IllegalStateException("查询改写数组解析失败: 数组项必须是字符串");
                }
                String value = item.asText();
                if (value.isBlank()) {
                    throw new IllegalStateException("查询改写数组解析失败: 数组项不能为空");
                }
                if (!value.equals(value.trim())) {
                    throw new IllegalStateException("查询改写数组解析失败: 数组项不能包含首尾空白");
                }
                rewrites.add(value);
            }
            return List.copyOf(rewrites);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("查询改写数组解析失败: " + e.getOriginalMessage(), e);
        }
    }

    public record RewriteResult(
            String primaryQuery,
            List<String> rewrittenQueries,
            Optional<float[]> hydeEmbedding
    ) {}
}
