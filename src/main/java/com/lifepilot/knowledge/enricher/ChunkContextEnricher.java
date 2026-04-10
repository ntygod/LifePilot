package com.lifepilot.knowledge.enricher;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.util.TokenCounter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.lifepilot.llm.LlmResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 分块上下文增强器 — 为每个分块生成文档级上下文前缀。
 *
 * <p>基于 Anthropic Contextual Retrieval 思路，通过 LLM 为每个分块生成
 * 简短的上下文描述前缀，提升检索召回率。LLM 不可用时优雅降级，返回原始分块。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ChunkContextEnricher {

    private static final Logger log = LoggerFactory.getLogger(ChunkContextEnricher.class);

    /** LLM 调用场景名称 */
    private static final String SCENE = LlmScene.KNOWLEDGE_EXTRACTION;

    private final @Nullable GenerationRouter generationRouter;
    private final KnowledgeBaseProperties.ContextEnricher config;
    private final PromptRegistry promptRegistry;
    private final TokenCounter tokenCounter;

    /**
     * 构造分块上下文增强器。
     *
     * @param generationRouter LLM 路由器（可为 null，运行时动态配置）
     * @param config           上下文增强配置
     * @param promptRegistry   提示词注册中心
     * @param tokenCounter     Token 计数器
     */
    public ChunkContextEnricher(@Nullable GenerationRouter generationRouter,
                                KnowledgeBaseProperties.ContextEnricher config,
                                PromptRegistry promptRegistry,
                                TokenCounter tokenCounter) {
        this.generationRouter = generationRouter;
        this.config = config;
        this.promptRegistry = promptRegistry;
        this.tokenCounter = tokenCounter;
        log.info("ChunkContextEnricher 初始化完成: enabled={}, maxPrefixTokens={}, generationRouter={}",
                config.enabled(), config.maxPrefixTokens(),
                generationRouter != null ? "已配置" : "未配置");
    }

    /**
     * 为分块列表生成上下文前缀。
     *
     * <p>逐个分块调用 LLM 生成上下文前缀。单个分块增强失败时跳过该分块，
     * LLM 完全不可用时返回原始分块列表。
     *
     * @param chunks          待增强的分块列表
     * @param documentSummary 文档摘要（用于 LLM 提示词）
     * @return 增强后的分块列表
     */
    public List<DocumentChunk> enrich(List<DocumentChunk> chunks, String documentSummary) {
        if (!config.enabled()) {
            log.debug("上下文增强已禁用，返回原始分块");
            return chunks;
        }
        if (generationRouter == null) {
            log.warn("GenerationRouter 不可用，跳过上下文增强: reason=路由器未配置");
            return chunks;
        }
        if (chunks.isEmpty()) {
            return chunks;
        }

        if (config.batchEnabled() && config.batchSize() > 1) {
            return enrichBatch(chunks, documentSummary);
        }
        return enrichOneByOne(chunks, documentSummary);
    }

    /**
     * 逐个分块调用 LLM 生成上下文前缀（原有逻辑）。
     */
    private List<DocumentChunk> enrichOneByOne(List<DocumentChunk> chunks, String documentSummary) {
        var enriched = new ArrayList<DocumentChunk>(chunks.size());
        int successCount = 0;

        for (var chunk : chunks) {
            try {
                var prefix = generatePrefix(chunk, documentSummary);
                enriched.add(chunk.withContextPrefix(prefix));
                successCount++;
            } catch (LlmUnavailableException e) {
                log.warn("LLM 不可用，跳过剩余分块上下文增强: {}", e.getMessage());
                enriched.add(chunk);
                int currentIndex = chunks.indexOf(chunk);
                for (int i = currentIndex + 1; i < chunks.size(); i++) {
                    enriched.add(chunks.get(i));
                }
                break;
            } catch (Exception e) {
                log.warn("分块上下文增强失败，跳过: chunkId={}, error={}", chunk.id(), e.getMessage());
                enriched.add(chunk);
            }
        }

        log.info("逐个上下文增强完成: 总分块={}, 成功增强={}", chunks.size(), successCount);
        return List.copyOf(enriched);
    }

    /**
     * 批量增强：将多个分块组合为单个 Prompt，一次 LLM 调用生成所有前缀。
     *
     * <p>按 batchSize 分组，每组构建批量 Prompt。Token 超限时自动拆批。
     * 前缀数量不匹配时回退到逐个调用。
     */
    private List<DocumentChunk> enrichBatch(List<DocumentChunk> chunks, String documentSummary) {
        var enriched = new ArrayList<DocumentChunk>(chunks.size());
        int batchSize = config.batchSize();
        int successCount = 0;
        int llmCallCount = 0;
        long startTime = System.currentTimeMillis();

        // 按 batchSize 分组
        List<List<DocumentChunk>> batches = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += batchSize) {
            batches.add(chunks.subList(i, Math.min(i + batchSize, chunks.size())));
        }

        for (var batch : batches) {
            try {
                // 检查 Prompt Token 数，超限时拆分为更小的批次
                List<List<DocumentChunk>> subBatches = splitIfTokenExceeded(batch);

                for (var subBatch : subBatches) {
                    var prompt = buildBatchPrompt(subBatch, documentSummary);
                    LlmResponse response = generationRouter.call(
                            SCENE,
                            prompt,
                            null,
                            null,
                            null,
                            GenerationCapability.CHAT,
                            null);
                    llmCallCount++;

                    List<String> prefixes = parseBatchResponse(response.content());

                    if (prefixes.size() != subBatch.size()) {
                        // 前缀数量不匹配，回退到逐个调用
                        log.warn("批量前缀数量不匹配: 期望={}, 实际={}, 回退到逐个调用",
                                subBatch.size(), prefixes.size());
                        for (var chunk : subBatch) {
                            try {
                                var prefix = generatePrefix(chunk, documentSummary);
                                enriched.add(chunk.withContextPrefix(prefix));
                                llmCallCount++;
                                successCount++;
                            } catch (Exception e) {
                                log.warn("逐个回退增强失败: chunkId={}", chunk.id());
                                enriched.add(chunk);
                            }
                        }
                    } else {
                        for (int i = 0; i < subBatch.size(); i++) {
                            String prefix = prefixes.get(i).trim();
                            int maxChars = config.maxPrefixTokens() * 4;
                            if (prefix.length() > maxChars) {
                                prefix = prefix.substring(0, maxChars);
                            }
                            enriched.add(subBatch.get(i).withContextPrefix(prefix));
                            successCount++;
                        }
                    }
                }
            } catch (LlmUnavailableException e) {
                log.warn("批量增强 LLM 不可用，剩余分块跳过: {}", e.getMessage());
                enriched.addAll(batch);
            } catch (Exception e) {
                log.warn("批量增强失败，回退到逐个调用: {}", e.getMessage());
                for (var chunk : batch) {
                    try {
                        var prefix = generatePrefix(chunk, documentSummary);
                        enriched.add(chunk.withContextPrefix(prefix));
                        llmCallCount++;
                        successCount++;
                    } catch (Exception ex) {
                        enriched.add(chunk);
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("批量上下文增强完成: 总分块={}, 成功增强={}, LLM调用次数={}, 耗时={}ms",
                chunks.size(), successCount, llmCallCount, elapsed);
        return List.copyOf(enriched);
    }

    /**
     * 检查批次 Prompt Token 是否超限，超限时拆分为更小的子批次。
     */
    private List<List<DocumentChunk>> splitIfTokenExceeded(List<DocumentChunk> batch) {
        int estimatedTokens = batch.stream()
                .mapToInt(c -> tokenCounter.countTokens(c.content()))
                .sum() + 200; // 200 Token 用于 Prompt 模板开销

        if (estimatedTokens <= config.maxPromptTokens()) {
            return List.of(batch);
        }

        // 拆分为更小的子批次
        List<List<DocumentChunk>> subBatches = new ArrayList<>();
        int currentTokens = 200;
        int start = 0;

        for (int i = 0; i < batch.size(); i++) {
            int chunkTokens = tokenCounter.countTokens(batch.get(i).content());
            if (currentTokens + chunkTokens > config.maxPromptTokens() && i > start) {
                subBatches.add(batch.subList(start, i));
                start = i;
                currentTokens = 200;
            }
            currentTokens += chunkTokens;
        }
        if (start < batch.size()) {
            subBatches.add(batch.subList(start, batch.size()));
        }

        log.debug("批次 Token 超限，拆分为 {} 个子批次", subBatches.size());
        return subBatches;
    }

    /**
     * 构建批量增强 Prompt。
     */
    private String buildBatchPrompt(List<DocumentChunk> chunks, String documentSummary) {
        var chunksText = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            chunksText.append("分块 ").append(i + 1).append("：\n");
            chunksText.append(chunks.get(i).content()).append("\n\n");
        }
        return promptRegistry.render("knowledge/chunk-context", Map.of(
                "maxPrefixTokens", String.valueOf(config.maxPrefixTokens()),
                "documentSummary", documentSummary,
                "chunks", chunksText.toString()));
    }

    /**
     * 解析批量 LLM 响应，提取 JSON 数组中的前缀列表。
     */
    private List<String> parseBatchResponse(String response) {
        // 简单解析 JSON 数组：提取 ["...", "...", ...] 中的字符串
        String trimmed = response.trim();
        // 找到第一个 [ 和最后一个 ]
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }

        String arrayContent = trimmed.substring(start + 1, end);
        List<String> prefixes = new ArrayList<>();
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
                    prefixes.add(current.toString());
                    current = new StringBuilder();
                }
                inString = !inString;
                continue;
            }
            if (inString) {
                current.append(c);
            }
        }
        return prefixes;
    }

    /**
     * 调用 LLM 为单个分块生成上下文前缀。
     */
    private String generatePrefix(DocumentChunk chunk, String documentSummary) {
        var prompt = buildPrompt(chunk, documentSummary);
        var response = generationRouter.call(
                SCENE,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                null);
        var prefix = response.content().trim();

        // 截断超长前缀（按字符粗略限制，实际 Token 数由 LLM 控制）
        int maxChars = config.maxPrefixTokens() * 4;
        if (prefix.length() > maxChars) {
            prefix = prefix.substring(0, maxChars);
        }
        return prefix;
    }

    /**
     * 构建 LLM 提示词。
     */
    private String buildPrompt(DocumentChunk chunk, String documentSummary) {
        return promptRegistry.render("knowledge/chunk-context-single", Map.of(
                "maxPrefixTokens", String.valueOf(config.maxPrefixTokens()),
                "documentSummary", documentSummary,
                "chunkContent", chunk.content()));
    }
}
