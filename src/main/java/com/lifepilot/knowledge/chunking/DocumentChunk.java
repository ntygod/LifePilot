package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.model.DocumentSourceType;

import java.util.ArrayList;
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
        Map<String, String> metadata,
        DocumentSourceType sourceType,
        Optional<String> parentChunkId,     // 父分块 ID（child 指向 parent）
        int chunkLevel                      // 分块层级：0=父块, 1=子块
) {

    public DocumentChunk {
        contextPrefix = contextPrefix != null ? contextPrefix : Optional.empty();
        headingHierarchy = headingHierarchy != null ? List.copyOf(headingHierarchy) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        sourceType = sourceType != null ? sourceType : DocumentSourceType.FILE;
        parentChunkId = parentChunkId != null ? parentChunkId : Optional.empty();
    }

    public DocumentChunk(
            String id,
            String documentId,
            String knowledgeBaseId,
            String content,
            Optional<String> contextPrefix,
            int chunkIndex,
            int startOffset,
            int endOffset,
            int tokenCount,
            String contentHash,
            List<String> headingHierarchy,
            int pageNumber,
            Map<String, String> metadata
    ) {
        this(id, documentId, knowledgeBaseId, content, contextPrefix, chunkIndex, startOffset,
                endOffset, tokenCount, contentHash, headingHierarchy, pageNumber, metadata,
                DocumentSourceType.FILE, Optional.empty(), 0);
    }

    /**
     * 生成用于 Embedding 的文本。
     *
     * <p>按优先级拼接：标题面包屑 → 上下文前缀 → 内容。
     * 标题面包屑提供章节定位上下文，上下文前缀提供文档级语义补充。
     *
     * @return Embedding 文本
     */
    public String embeddingText() {
        var parts = new ArrayList<String>();
        if (!headingHierarchy.isEmpty()) {
            parts.add(String.join(" > ", headingHierarchy));
        }
        contextPrefix.ifPresent(parts::add);
        parts.add(content);
        return String.join("\n\n", parts);
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

    /**
     * 创建填充了文档上下文信息的新分块实例。
     *
     * @param documentId        文档 ID
     * @param knowledgeBaseId   知识库 ID
     * @param sourceType        来源类型
     * @return 新的 DocumentChunk 实例
     */
    public DocumentChunk withDocumentContext(String documentId, String knowledgeBaseId,
                                             DocumentSourceType sourceType) {
        return new DocumentChunk(id(), documentId, knowledgeBaseId, content(), contextPrefix(),
                chunkIndex(), startOffset(), endOffset(), tokenCount(), contentHash(),
                headingHierarchy(), pageNumber(), metadata(),
                sourceType, parentChunkId(), chunkLevel());
    }

    /**
     * 创建带上下文前缀的新分块实例。
     *
     * @param prefix 上下文前缀文本
     * @return 新的 DocumentChunk 实例
     */
    public DocumentChunk withContextPrefix(String prefix) {
        return new DocumentChunk(id(), documentId(), knowledgeBaseId(), content(), Optional.of(prefix),
                chunkIndex(), startOffset(), endOffset(), tokenCount(), contentHash(),
                headingHierarchy(), pageNumber(), metadata(),
                sourceType(), parentChunkId(), chunkLevel());
    }

    /**
     * 创建带父子关系的新分块实例。
     *
     * @param parentChunkId 父分块 ID
     * @param chunkLevel    分块层级（0=父块, 1=子块）
     * @return 新的 DocumentChunk 实例
     */
    public DocumentChunk withParentContext(String parentChunkId, int chunkLevel) {
        return new DocumentChunk(id(), documentId(), knowledgeBaseId(), content(), contextPrefix(),
                chunkIndex(), startOffset(), endOffset(), tokenCount(), contentHash(),
                headingHierarchy(), pageNumber(), metadata(),
                sourceType(), Optional.ofNullable(parentChunkId), chunkLevel);
    }
}
