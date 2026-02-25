package com.lifepilot.knowledge.enricher;

import com.lifepilot.knowledge.chunking.DocumentChunk;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private static final String SCENE = "knowledge_extraction";

    private final LlmRouter llmRouter;
    private final KnowledgeBaseProperties.ContextEnricher config;

    /**
     * 构造分块上下文增强器。
     *
     * @param llmRouter LLM 路由器
     * @param config    上下文增强配置
     */
    public ChunkContextEnricher(LlmRouter llmRouter, KnowledgeBaseProperties.ContextEnricher config) {
        this.llmRouter = llmRouter;
        this.config = config;
        log.info("ChunkContextEnricher 初始化完成: enabled={}, maxPrefixTokens={}",
                config.enabled(), config.maxPrefixTokens());
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
        if (chunks.isEmpty()) {
            return chunks;
        }

        var enriched = new ArrayList<DocumentChunk>(chunks.size());
        int successCount = 0;

        for (var chunk : chunks) {
            try {
                var prefix = generatePrefix(chunk, documentSummary);
                enriched.add(withContextPrefix(chunk, prefix));
                successCount++;
            } catch (LlmUnavailableException e) {
                // LLM 完全不可用，后续分块也不再尝试
                log.warn("LLM 不可用，跳过剩余分块上下文增强: {}", e.getMessage());
                enriched.add(chunk);
                // 将剩余分块原样添加
                int currentIndex = chunks.indexOf(chunk);
                for (int i = currentIndex + 1; i < chunks.size(); i++) {
                    enriched.add(chunks.get(i));
                }
                break;
            } catch (Exception e) {
                // 单个分块增强失败，跳过继续
                log.warn("分块上下文增强失败，跳过: chunkId={}, error={}", chunk.id(), e.getMessage());
                enriched.add(chunk);
            }
        }

        log.info("上下文增强完成: 总分块={}, 成功增强={}", chunks.size(), successCount);
        return List.copyOf(enriched);
    }

    /**
     * 调用 LLM 为单个分块生成上下文前缀。
     */
    private String generatePrefix(DocumentChunk chunk, String documentSummary) {
        var prompt = buildPrompt(chunk, documentSummary);
        var response = llmRouter.call(SCENE, prompt, null);
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
        return """
                请为以下文档分块生成一段简短的上下文描述（不超过 %d 个 Token），\
                说明该分块在文档中的位置和主题，帮助提升检索准确性。
                
                文档摘要：%s
                
                分块内容：
                %s
                
                请直接输出上下文描述，不要包含任何前缀或解释。""".formatted(
                config.maxPrefixTokens(),
                documentSummary,
                chunk.content()
        );
    }

    /**
     * 创建带有上下文前缀的新 DocumentChunk 实例。
     */
    private DocumentChunk withContextPrefix(DocumentChunk chunk, String prefix) {
        return new DocumentChunk(
                chunk.id(),
                chunk.documentId(),
                chunk.knowledgeBaseId(),
                chunk.content(),
                Optional.of(prefix),
                chunk.chunkIndex(),
                chunk.startOffset(),
                chunk.endOffset(),
                chunk.tokenCount(),
                chunk.contentHash(),
                chunk.headingHierarchy(),
                chunk.pageNumber(),
                chunk.metadata()
        );
    }
}
