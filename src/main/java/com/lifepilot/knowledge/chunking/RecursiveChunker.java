package com.lifepilot.knowledge.chunking;

import java.util.List;
import java.util.Map;

/**
 * 递归语义分块器 — 在自然边界处递归切分。
 *
 * <p>分隔符优先级：段落（\n\n）→ 句子（。！？.!?）→ 词（空格/中文字符边界）。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class RecursiveChunker implements ChunkingStrategy {

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        // TODO: Task 4.1 实现
        throw new UnsupportedOperationException("RecursiveChunker 尚未实现");
    }

    @Override
    public int estimateChunkCount(int textLength) {
        // TODO: Task 4.1 实现
        throw new UnsupportedOperationException("RecursiveChunker 尚未实现");
    }

    @Override
    public String strategyName() {
        return "recursive";
    }
}
