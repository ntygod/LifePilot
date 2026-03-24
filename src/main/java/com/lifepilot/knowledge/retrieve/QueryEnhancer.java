package com.lifepilot.knowledge.retrieve;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 查询增强器，通过生成式改写或 HyDE 补全文本来提升知识检索质量。
 *
 * <p>支持三种模式：
 * <ul>
 *   <li>{@code rewrite}：调用生成模型产生若干改写变体</li>
 *   <li>{@code hyde}：先生成假设性文档，再转成向量</li>
 *   <li>{@code none}：不做增强，直接返回原始查询</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-06
 */
public class QueryEnhancer {

    private static final Logger log = LoggerFactory.getLogger(QueryEnhancer.class);
    private static final String SCENE = "query_enhance";

    private final GenerationRouter generationRouter;
    private final EmbeddingRouter embeddingRouter;
    private final KnowledgeBaseProperties.QueryEnhancer config;
    private final PromptRegistry promptRegistry;

    public QueryEnhancer(GenerationRouter generationRouter,
                         EmbeddingRouter embeddingRouter,
                         KnowledgeBaseProperties.QueryEnhancer config,
                         PromptRegistry promptRegistry) {
        this.generationRouter = generationRouter;
        this.embeddingRouter = embeddingRouter;
        this.config = config;
        this.promptRegistry = promptRegistry;
        log.debug("初始化 QueryEnhancer: mode={}, timeoutMs={}, maxRewrites={}",
                config.mode(), config.timeoutMs(), config.maxRewrites());
    }

    public EnhancedQuery enhance(String originalQuery) {
        long start = System.currentTimeMillis();
        try {
            EnhancedQuery result = switch (config.mode()) {
                case "rewrite" -> rewriteQuery(originalQuery);
                case "hyde" -> hydeQuery(originalQuery);
                default -> new EnhancedQuery(originalQuery, List.of(), Optional.empty());
            };
            long elapsed = System.currentTimeMillis() - start;
            log.debug("查询增强完成: mode={}, originalQuery={}, rewrites={}, elapsedMs={}",
                    config.mode(), originalQuery, result.rewrittenQueries().size(), elapsed);
            return result;
        } catch (LlmUnavailableException e) {
            log.warn("查询增强不可用，降级返回原始查询: {}", e.getMessage());
            return new EnhancedQuery(originalQuery, List.of(), Optional.empty());
        } catch (Exception e) {
            log.warn("查询增强失败，降级返回原始查询: {}", e.getMessage());
            return new EnhancedQuery(originalQuery, List.of(), Optional.empty());
        }
    }

    private EnhancedQuery rewriteQuery(String originalQuery) {
        String prompt = promptRegistry.render("knowledge/query-rewrite", Map.of(
                "maxRewrites", String.valueOf(config.maxRewrites()),
                "originalQuery", originalQuery));

        String responseText = callWithTimeout(prompt);
        List<String> rewrites = parseJsonArray(responseText);
        if (rewrites.isEmpty()) {
            return new EnhancedQuery(originalQuery, List.of(), Optional.empty());
        }
        if (rewrites.size() > config.maxRewrites()) {
            rewrites = rewrites.subList(0, config.maxRewrites());
        }
        return new EnhancedQuery(originalQuery, List.copyOf(rewrites), Optional.empty());
    }

    private EnhancedQuery hydeQuery(String originalQuery) {
        String prompt = promptRegistry.render("knowledge/hyde-generation", Map.of(
                "originalQuery", originalQuery));
        String hypotheticalDoc = callWithTimeout(prompt);
        float[] embedding = embeddingRouter.embed(hypotheticalDoc, EmbeddingUseCase.KNOWLEDGE_BASE, null, null);
        return new EnhancedQuery(originalQuery, List.of(), Optional.of(embedding));
    }

    private String callWithTimeout(String prompt) {
        return generationRouter.call(
                SCENE,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                Duration.ofMillis(config.timeoutMs())
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

    public record EnhancedQuery(
            String primaryQuery,
            List<String> rewrittenQueries,
            Optional<float[]> hydeEmbedding
    ) {}
}
