package com.lifepilot.knowledge.chunking;

import java.util.List;
import java.util.Map;

/**
 * 标题层级分块器 — 按文档标题结构切分。
 *
 * <p>每个标题节作为一个分块，超长节委托 {@link RecursiveChunker} 二次切分。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class HeadingChunker implements ChunkingStrategy {

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        // TODO: Task 4.6 实现
        throw new UnsupportedOperationException("HeadingChunker 尚未实现");
    }

    @Override
    public int estimateChunkCount(int textLength) {
        // TODO: Task 4.6 实现
        throw new UnsupportedOperationException("HeadingChunker 尚未实现");
    }

    @Override
    public String strategyName() {
        return "heading";
    }
}
