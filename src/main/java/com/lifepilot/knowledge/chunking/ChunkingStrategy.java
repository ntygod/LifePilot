package com.lifepilot.knowledge.chunking;

import java.util.List;
import java.util.Map;

/**
 * 分块策略 — 将文档文本切分为 {@link DocumentChunk} 列表的策略接口。
 *
 * <p>sealed interface，当前仅允许 {@link FixedSizeChunker} 实现。
 * 后续 spec 扩展 permits 列表添加 RecursiveChunker、HeadingChunker、SmartChunker。
 *
 * @author zsg
 * @since 2026-02-25
 */
public sealed interface ChunkingStrategy
        permits FixedSizeChunker, RecursiveChunker, HeadingChunker, SmartChunker, SemanticChunker {

    /**
     * 将文本切分为文档分块列表。
     *
     * @param text     待分块的文本内容
     * @param metadata 附加元数据，传递给每个分块
     * @return 分块列表
     */
    List<DocumentChunk> chunk(String text, Map<String, String> metadata);

    /**
     * 估算给定文本长度会产生的分块数量。
     *
     * @param textLength 文本字符长度
     * @return 预估分块数
     */
    int estimateChunkCount(int textLength);

    /**
     * 返回策略名称标识。
     *
     * @return 策略名称
     */
    String strategyName();
}
