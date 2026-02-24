package com.lifepilot.knowledge.chunking;

import java.util.List;
import java.util.Map;

/**
 * 固定大小分块器 — 按固定字符数切分文本，支持重叠和句子边界对齐。
 *
 * <p>当前为占位实现，完整逻辑将在 Task 8.1 中实现。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class FixedSizeChunker implements ChunkingStrategy {

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        throw new UnsupportedOperationException("FixedSizeChunker 尚未实现，将在 Task 8.1 中完成");
    }

    @Override
    public int estimateChunkCount(int textLength) {
        throw new UnsupportedOperationException("FixedSizeChunker 尚未实现，将在 Task 8.1 中完成");
    }

    @Override
    public String strategyName() {
        return "fixed-size";
    }
}
