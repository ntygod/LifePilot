package com.lifepilot.knowledge.chunking;

import java.util.List;
import java.util.Map;

/**
 * 智能策略选择器 — 分析文档特征自动选择最佳分块策略。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class SmartChunker implements ChunkingStrategy {

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        // TODO: Task 4.10 实现
        throw new UnsupportedOperationException("SmartChunker 尚未实现");
    }

    @Override
    public int estimateChunkCount(int textLength) {
        // TODO: Task 4.10 实现
        throw new UnsupportedOperationException("SmartChunker 尚未实现");
    }

    @Override
    public String strategyName() {
        return "smart";
    }
}
