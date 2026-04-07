package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.util.TokenCounter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FixedSizeChunker 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class FixedSizeChunkerTest {

    // ========== 空输入 ==========

    @Test
    void 空输入_null文本返回空列表() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        List<DocumentChunk> chunks = chunker.chunk(null, Map.of());
        assertTrue(chunks.isEmpty());
    }

    @Test
    void 空输入_空白文本返回空列表() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        assertTrue(chunker.chunk("", Map.of()).isEmpty());
        assertTrue(chunker.chunk("   ", Map.of()).isEmpty());
        assertTrue(chunker.chunk("\n\t\n", Map.of()).isEmpty());
    }

    // ========== strategyName ==========

    @Test
    void strategyName_返回fixedSize() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        assertEquals("fixed-size", chunker.strategyName());
    }

    // ========== 非空文本至少产生一个分块 ==========

    @Test
    void 非空文本_至少产生一个分块() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        List<DocumentChunk> chunks = chunker.chunk("Hello", Map.of());
        assertFalse(chunks.isEmpty());
    }

    @Test
    void 非空文本_短于maxChunkSize产生一个分块() {
        var config = new ChunkingConfig(100, 10, 0, 50, false, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        List<DocumentChunk> chunks = chunker.chunk("短文本", Map.of());
        assertEquals(1, chunks.size());
    }

    // ========== 分块大小边界 ==========

    @Test
    void 分块大小边界_每个分块内容长度不超过maxChunkSize() {
        var config = new ChunkingConfig(50, 5, 0, 50, false, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        String text = "a".repeat(200);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= config.maxChunkSize(),
                    "分块 #" + chunk.chunkIndex() + " 长度 " + chunk.content().length()
                            + " 超过 maxChunkSize " + config.maxChunkSize());
        }
    }

    @Test
    void 分块大小边界_使用SMALL配置() {
        var chunker = new FixedSizeChunker(ChunkingConfig.SMALL, new TokenCounter.Heuristic());
        // 生成超过 SMALL.maxChunkSize(512) 的文本
        String text = "这是一段测试文本。".repeat(100);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        assertTrue(chunks.size() > 1, "应产生多个分块");
        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= ChunkingConfig.SMALL.maxChunkSize(),
                    "分块内容长度不应超过 SMALL.maxChunkSize");
        }
    }


    @Test
    void 分块大小边界_使用LARGE配置() {
        var chunker = new FixedSizeChunker(ChunkingConfig.LARGE, new TokenCounter.Heuristic());
        String text = "Hello world. ".repeat(500);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        assertTrue(chunks.size() > 1, "应产生多个分块");
        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.content().length() <= ChunkingConfig.LARGE.maxChunkSize(),
                    "分块内容长度不应超过 LARGE.maxChunkSize");
        }
    }

    // ========== 重叠 ==========

    @Test
    void 重叠_连续分块共享重叠内容() {
        // 关闭句子边界对齐，确保精确切分
        var config = new ChunkingConfig(50, 5, 10, 50, false, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        String text = "a".repeat(150);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        assertTrue(chunks.size() > 1, "应产生多个分块");
        for (int i = 1; i < chunks.size(); i++) {
            DocumentChunk prev = chunks.get(i - 1);
            DocumentChunk curr = chunks.get(i);
            // 当前分块的起始偏移量应在前一个分块的结束偏移量之前（重叠区域）
            assertTrue(curr.startOffset() < prev.endOffset(),
                    "分块 #" + i + " 的 startOffset(" + curr.startOffset()
                            + ") 应小于前一个分块的 endOffset(" + prev.endOffset() + ")");
        }
    }

    @Test
    void 重叠_overlapSize为0时无重叠() {
        var config = new ChunkingConfig(50, 5, 0, 50, false, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        String text = "a".repeat(150);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        assertTrue(chunks.size() > 1, "应产生多个分块");
        for (int i = 1; i < chunks.size(); i++) {
            DocumentChunk prev = chunks.get(i - 1);
            DocumentChunk curr = chunks.get(i);
            // 无重叠时，当前分块起始 >= 前一个分块结束
            assertTrue(curr.startOffset() >= prev.endOffset(),
                    "无重叠时分块 #" + i + " 的 startOffset 应 >= 前一个分块的 endOffset");
        }
    }

    // ========== 句子边界对齐 ==========

    @Test
    void 句子边界对齐_respectSentences为true时在句子边界切分() {
        var config = new ChunkingConfig(50, 5, 0, 50, true, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        // 构造包含句子边界的文本，每句约 20 字符
        String text = "这是第一个句子内容。这是第二个句子内容。这是第三个句子内容。这是第四个句子内容。";
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        // 检查除最后一个分块外，每个分块应以句子结束标点结尾
        for (int i = 0; i < chunks.size() - 1; i++) {
            String content = chunks.get(i).content();
            char lastChar = content.charAt(content.length() - 1);
            assertTrue("。！？；.!?\n".indexOf(lastChar) >= 0,
                    "分块 #" + i + " 应以句子结束标点结尾，实际末尾字符: '" + lastChar + "'");
        }
    }

    @Test
    void 句子边界对齐_英文句子边界() {
        var config = new ChunkingConfig(60, 5, 0, 50, true, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        String text = "This is sentence one. This is sentence two. This is sentence three. This is the end.";
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        // 至少产生多个分块
        assertTrue(chunks.size() > 1, "应产生多个分块");
        // 检查非最后分块以句子标点结尾
        for (int i = 0; i < chunks.size() - 1; i++) {
            String content = chunks.get(i).content();
            char lastChar = content.charAt(content.length() - 1);
            assertTrue("。！？；.!?\n".indexOf(lastChar) >= 0,
                    "英文分块 #" + i + " 应以句子结束标点结尾，实际: '" + lastChar + "'");
        }
    }

    @Test
    void 句子边界对齐_respectSentences为false时不对齐() {
        var config = new ChunkingConfig(50, 5, 0, 50, false, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        String text = "a".repeat(120);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        // 无句子边界对齐，分块应精确按 maxChunkSize 切分
        assertEquals(50, chunks.get(0).content().length());
    }

    // ========== 最小分块合并 ==========

    @Test
    void 最小分块合并_最后分块过小时与前一个合并() {
        // maxChunkSize=50, minChunkSize=20, 文本长度 60 → 第一块 50，剩余 10 < 20 → 合并
        var config = new ChunkingConfig(50, 20, 0, 50, false, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        String text = "x".repeat(60);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        // 应合并为一个分块
        assertEquals(1, chunks.size(), "最后分块过小应与前一个合并");
    }

    @Test
    void 最小分块合并_最后分块足够大时不合并() {
        // maxChunkSize=50, minChunkSize=10, 文本长度 80 → 第一块 50，剩余 30 >= 10 → 不合并
        var config = new ChunkingConfig(50, 10, 0, 50, false, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        String text = "x".repeat(80);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        assertEquals(2, chunks.size(), "最后分块足够大时不应合并");
    }

    // ========== 分块元数据完整性 ==========

    @Test
    void 分块元数据_每个分块有非空UUID_id() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        List<DocumentChunk> chunks = chunker.chunk("测试文本内容", Map.of());

        for (DocumentChunk chunk : chunks) {
            assertNotNull(chunk.id(), "分块 id 不应为 null");
            assertFalse(chunk.id().isBlank(), "分块 id 不应为空白");
        }
    }

    @Test
    void 分块元数据_contentHash为64字符十六进制SHA256() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        List<DocumentChunk> chunks = chunker.chunk("测试文本内容", Map.of());

        for (DocumentChunk chunk : chunks) {
            assertNotNull(chunk.contentHash(), "contentHash 不应为 null");
            assertEquals(64, chunk.contentHash().length(),
                    "SHA-256 哈希应为 64 字符");
            assertTrue(chunk.contentHash().matches("[0-9a-f]{64}"),
                    "contentHash 应为小写十六进制: " + chunk.contentHash());
        }
    }

    @Test
    void 分块元数据_tokenCount非负() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        List<DocumentChunk> chunks = chunker.chunk("测试文本内容 with English", Map.of());

        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.tokenCount() >= 0,
                    "tokenCount 应非负: " + chunk.tokenCount());
        }
    }

    @Test
    void 分块元数据_多个分块id各不相同() {
        var config = new ChunkingConfig(50, 5, 0, 50, false, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        String text = "a".repeat(200);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        long distinctIds = chunks.stream().map(DocumentChunk::id).distinct().count();
        assertEquals(chunks.size(), distinctIds, "每个分块的 id 应唯一");
    }

    @Test
    void 分块元数据_chunkIndex从0递增() {
        var config = new ChunkingConfig(50, 5, 0, 50, false, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        String text = "a".repeat(200);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).chunkIndex(),
                    "分块 #" + i + " 的 chunkIndex 应为 " + i);
        }
    }

    // ========== 分块内容是原文子串 ==========

    @Test
    void 分块内容_是原文子串() {
        var config = new ChunkingConfig(100, 10, 20, 50, false, false, false);
        var chunker = new FixedSizeChunker(config, new TokenCounter.Heuristic());
        String text = "这是一段包含中文和English的混合文本，用于测试分块内容是否为原文子串。" +
                "第二段内容继续扩展文本长度，确保产生多个分块。" +
                "第三段内容进一步增加长度，验证所有分块都是原文的子串。";
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        for (DocumentChunk chunk : chunks) {
            String trimmedContent = chunk.content().trim();
            assertTrue(text.contains(trimmedContent),
                    "分块内容应为原文子串: \"" + trimmedContent.substring(0, Math.min(30, trimmedContent.length())) + "...\"");
        }
    }

    @Test
    void 分块内容_使用SMALL配置也是原文子串() {
        var chunker = new FixedSizeChunker(ChunkingConfig.SMALL, new TokenCounter.Heuristic());
        String text = "Hello world. ".repeat(100);
        List<DocumentChunk> chunks = chunker.chunk(text, Map.of());

        for (DocumentChunk chunk : chunks) {
            String trimmedContent = chunk.content().trim();
            assertTrue(text.contains(trimmedContent),
                    "SMALL 配置下分块内容应为原文子串");
        }
    }

    // ========== estimateChunkCount ==========

    @Test
    void estimateChunkCount_返回合理估算值() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        // DEFAULT: maxChunkSize=1024, overlapSize=128, effectiveStep=896
        int textLength = 5000;
        int estimate = chunker.estimateChunkCount(textLength);

        assertTrue(estimate >= 1, "估算值应至少为 1");
        // 合理范围：实际分块数不应偏离估算太远
        int expectedMin = (int) Math.ceil((double) textLength / ChunkingConfig.DEFAULT.maxChunkSize());
        int expectedMax = (int) Math.ceil((double) textLength /
                (ChunkingConfig.DEFAULT.maxChunkSize() - ChunkingConfig.DEFAULT.overlapSize())) + 1;
        assertTrue(estimate >= expectedMin && estimate <= expectedMax,
                "估算值 " + estimate + " 应在合理范围 [" + expectedMin + ", " + expectedMax + "]");
    }

    @Test
    void estimateChunkCount_文本长度为0返回0() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        assertEquals(0, chunker.estimateChunkCount(0));
    }

    @Test
    void estimateChunkCount_负数文本长度返回0() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        assertEquals(0, chunker.estimateChunkCount(-1));
    }

    @Test
    void estimateChunkCount_短文本返回1() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        assertEquals(1, chunker.estimateChunkCount(100));
    }

    // ========== 传递 metadata ==========

    @Test
    void metadata_传递给分块() {
        var chunker = new FixedSizeChunker(ChunkingConfig.DEFAULT, new TokenCounter.Heuristic());
        Map<String, String> metadata = Map.of("source", "test", "lang", "zh");
        List<DocumentChunk> chunks = chunker.chunk("测试文本", metadata);

        assertFalse(chunks.isEmpty());
        for (DocumentChunk chunk : chunks) {
            assertEquals(metadata, chunk.metadata());
        }
    }
}
