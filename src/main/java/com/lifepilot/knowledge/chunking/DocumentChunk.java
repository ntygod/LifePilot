package com.lifepilot.knowledge.chunking;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 文档分块 — 文档被切分后的最小检索单元。
 *
 * <p>携带内容、偏移量、标题层级和内容哈希，用于向量检索和知识提取。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record DocumentChunk(
        String id,                          // UUID
        String documentId,
        String knowledgeBaseId,
        String content,
        Optional<String> contextPrefix,
        int chunkIndex,
        int startOffset,
        int endOffset,
        int tokenCount,
        String contentHash,                 // SHA-256
        List<String> headingHierarchy,
        int pageNumber,
        Map<String, String> metadata
) {

    public DocumentChunk {
        contextPrefix = contextPrefix != null ? contextPrefix : Optional.empty();
        headingHierarchy = headingHierarchy != null ? List.copyOf(headingHierarchy) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * 生成用于 Embedding 的文本。
     *
     * <p>当 contextPrefix 存在时，将其与 content 拼接；否则直接返回 content。
     *
     * @return Embedding 文本
     */
    public String embeddingText() {
        return contextPrefix.map(prefix -> prefix + "\n\n" + content).orElse(content);
    }

    /**
     * 生成标题层级面包屑路径。
     *
     * @return 以 " > " 分隔的标题层级字符串
     */
    public String breadcrumb() {
        return String.join(" > ", headingHierarchy);
    }

    /**
     * 返回分块内容的字符长度。
     *
     * @return 内容字符数
     */
    public int contentLength() {
        return content.length();
    }
}
