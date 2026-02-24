package com.lifepilot.knowledge.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DocumentChunk record 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class DocumentChunkTest {

    private DocumentChunk createChunk(String content, Optional<String> contextPrefix,
                                      List<String> headingHierarchy) {
        return new DocumentChunk(
                "chunk-id-1", "doc-id-1", "kb-id-1",
                content, contextPrefix,
                0, 0, content.length(),
                100, "abc123hash",
                headingHierarchy, 1, Map.of()
        );
    }

    // --- embeddingText() ---

    @Test
    void embeddingText_有contextPrefix时拼接前缀和内容() {
        var chunk = createChunk("正文内容", Optional.of("上下文前缀"), List.of());
        assertEquals("上下文前缀\n\n正文内容", chunk.embeddingText());
    }

    @Test
    void embeddingText_无contextPrefix时返回content() {
        var chunk = createChunk("正文内容", Optional.empty(), List.of());
        assertEquals("正文内容", chunk.embeddingText());
    }

    // --- breadcrumb() ---

    @Test
    void breadcrumb_多级标题用分隔符连接() {
        var chunk = createChunk("内容", Optional.empty(), List.of("第一章", "第一节", "概述"));
        assertEquals("第一章 > 第一节 > 概述", chunk.breadcrumb());
    }

    @Test
    void breadcrumb_单级标题直接返回() {
        var chunk = createChunk("内容", Optional.empty(), List.of("标题"));
        assertEquals("标题", chunk.breadcrumb());
    }

    @Test
    void breadcrumb_空标题层级返回空字符串() {
        var chunk = createChunk("内容", Optional.empty(), List.of());
        assertEquals("", chunk.breadcrumb());
    }

    // --- contentLength() ---

    @Test
    void contentLength_返回内容字符数() {
        var chunk = createChunk("hello world", Optional.empty(), List.of());
        assertEquals(11, chunk.contentLength());
    }

    @Test
    void contentLength_中文内容返回正确字符数() {
        var chunk = createChunk("你好世界", Optional.empty(), List.of());
        assertEquals(4, chunk.contentLength());
    }

    @Test
    void contentLength_空内容返回0() {
        var chunk = createChunk("", Optional.empty(), List.of());
        assertEquals(0, chunk.contentLength());
    }
}
