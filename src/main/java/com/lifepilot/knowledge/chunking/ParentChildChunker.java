package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.util.TextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Parent-Child 分块器 — 生成两级分块实现"小块检索、大块返回"。
 *
 * <p>算法流程：
 * <ol>
 *   <li>用 parentChunker 将文档切成大块（level=0, parent），用于返回给 LLM</li>
 *   <li>对每个 parent 块用 childChunker 切成小块（level=1, child），用于向量检索</li>
 *   <li>child 块通过 parentChunkId 引用其所属的 parent 块</li>
 * </ol>
 *
 * <p>向量索引只索引 child 块（精准命中），检索命中后返回对应 parent 块（上下文完整）。
 *
 * @author zsg
 * @since 2026-04-07
 */
public non-sealed class ParentChildChunker implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(ParentChildChunker.class);

    private final ChunkingStrategy parentChunker;
    private final ChunkingStrategy childChunker;

    /**
     * 构造 Parent-Child 分块器。
     *
     * @param parentChunker 大块策略（生成 parent 分块）
     * @param childChunker  小块策略（对每个 parent 生成 child 分块）
     */
    public ParentChildChunker(ChunkingStrategy parentChunker, ChunkingStrategy childChunker) {
        this.parentChunker = parentChunker;
        this.childChunker = childChunker;
        log.debug("初始化 ParentChildChunker: parentStrategy={}, childStrategy={}",
                parentChunker.strategyName(), childChunker.strategyName());
    }

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 1. 用 parentChunker 切出大块（level=0）
        List<DocumentChunk> parentChunks = parentChunker.chunk(text, metadata);
        List<DocumentChunk> result = new ArrayList<>();
        int globalIndex = 0;

        for (var parent : parentChunks) {
            // 2. 对每个 parent 内容用 childChunker 切成小块
            List<DocumentChunk> children = childChunker.chunk(parent.content(), metadata);

            // 子块只有一个且内容与父块相同 → 不拆分，直接保留父块（VectorIndexer 会索引所有 level=0 的无子块块）
            if (children.size() <= 1) {
                result.add(withIndex(parent.withParentContext(null, 0), globalIndex++));
                continue;
            }

            // 标记为 parent（level=0），使用全局统一编号
            var parentWithLevel = withIndex(parent.withParentContext(null, 0), globalIndex++);
            result.add(parentWithLevel);

            // 3. 创建子块（level=1），继承父块标题层级，偏移量调整为原始文档的绝对偏移量
            for (var child : children) {
                var adjustedChild = new DocumentChunk(
                        child.id(),
                        child.documentId(),
                        child.knowledgeBaseId(),
                        child.content(),
                        child.contextPrefix(),
                        globalIndex++,
                        parent.startOffset() + child.startOffset(),
                        parent.startOffset() + child.endOffset(),
                        child.tokenCount(),
                        child.contentHash(),
                        parent.headingHierarchy(),
                        child.pageNumber(),
                        child.metadata(),
                        child.sourceType(),
                        child.sourceDatastoreId(),
                        child.sourceCollectionId(),
                        Optional.of(parentWithLevel.id()),
                        1
                );
                result.add(adjustedChild);
            }
        }

        long parentCount = result.stream().filter(c -> c.chunkLevel() == 0).count();
        long childCount = result.stream().filter(c -> c.chunkLevel() == 1).count();
        log.debug("Parent-Child 分块完成: parent={}, child={}, 总计={}",
                parentCount, childCount, result.size());

        return List.copyOf(result);
    }

    /**
     * 设置分块的 chunkIndex。
     */
    private DocumentChunk withIndex(DocumentChunk chunk, int index) {
        return new DocumentChunk(
                chunk.id(), chunk.documentId(), chunk.knowledgeBaseId(),
                chunk.content(), chunk.contextPrefix(), index,
                chunk.startOffset(), chunk.endOffset(), chunk.tokenCount(),
                chunk.contentHash(), chunk.headingHierarchy(), chunk.pageNumber(),
                chunk.metadata(), chunk.sourceType(), chunk.sourceDatastoreId(),
                chunk.sourceCollectionId(), chunk.parentChunkId(), chunk.chunkLevel());
    }

    @Override
    public int estimateChunkCount(int textLength) {
        // parent 数量 + 每个 parent 的 child 数量
        int parentCount = parentChunker.estimateChunkCount(textLength);
        int avgParentSize = textLength / Math.max(1, parentCount);
        int childPerParent = childChunker.estimateChunkCount(avgParentSize);
        return parentCount + parentCount * childPerParent;
    }

    @Override
    public String strategyName() {
        return "parent-child";
    }
}
