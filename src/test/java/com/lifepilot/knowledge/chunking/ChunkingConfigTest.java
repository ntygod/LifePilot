package com.lifepilot.knowledge.chunking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChunkingConfig record 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class ChunkingConfigTest {

    // --- 参数验证 ---

    @Test
    void 构造器_maxChunkSize为0时抛出异常() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkingConfig(0, 0, 0, 512, true, true, true));
    }

    @Test
    void 构造器_maxChunkSize为负数时抛出异常() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkingConfig(-1, 0, 0, 512, true, true, true));
    }

    @Test
    void 构造器_minChunkSize为负数时抛出异常() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkingConfig(1024, -1, 0, 512, true, true, true));
    }

    @Test
    void 构造器_minChunkSize等于maxChunkSize时抛出异常() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkingConfig(1024, 1024, 0, 512, true, true, true));
    }

    @Test
    void 构造器_minChunkSize大于maxChunkSize时抛出异常() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkingConfig(1024, 2000, 0, 512, true, true, true));
    }

    @Test
    void 构造器_overlapSize为负数时抛出异常() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkingConfig(1024, 100, -1, 512, true, true, true));
    }

    @Test
    void 构造器_overlapSize等于maxChunkSize时抛出异常() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkingConfig(1024, 100, 1024, 512, true, true, true));
    }

    @Test
    void 构造器_overlapSize大于maxChunkSize时抛出异常() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkingConfig(1024, 100, 2000, 512, true, true, true));
    }

    @Test
    void 构造器_合法参数正常创建() {
        var config = new ChunkingConfig(1024, 100, 128, 512, true, true, true);
        assertEquals(1024, config.maxChunkSize());
        assertEquals(100, config.minChunkSize());
        assertEquals(128, config.overlapSize());
        assertEquals(512, config.maxChunkTokens());
        assertTrue(config.respectSentences());
        assertTrue(config.respectParagraphs());
        assertTrue(config.enableContextPrefix());
    }

    @Test
    void 构造器_边界值minChunkSize为0和overlapSize为0正常创建() {
        var config = new ChunkingConfig(100, 0, 0, 50, false, false, false);
        assertEquals(0, config.minChunkSize());
        assertEquals(0, config.overlapSize());
    }

    // --- 静态常量 ---

    @Test
    void DEFAULT常量值正确() {
        assertEquals(1024, ChunkingConfig.DEFAULT.maxChunkSize());
        assertEquals(100, ChunkingConfig.DEFAULT.minChunkSize());
        assertEquals(128, ChunkingConfig.DEFAULT.overlapSize());
        assertEquals(512, ChunkingConfig.DEFAULT.maxChunkTokens());
        assertTrue(ChunkingConfig.DEFAULT.respectSentences());
        assertTrue(ChunkingConfig.DEFAULT.respectParagraphs());
        assertTrue(ChunkingConfig.DEFAULT.enableContextPrefix());
    }

    @Test
    void SMALL常量值正确() {
        assertEquals(512, ChunkingConfig.SMALL.maxChunkSize());
        assertEquals(50, ChunkingConfig.SMALL.minChunkSize());
        assertEquals(64, ChunkingConfig.SMALL.overlapSize());
        assertEquals(256, ChunkingConfig.SMALL.maxChunkTokens());
        assertTrue(ChunkingConfig.SMALL.respectSentences());
        assertTrue(ChunkingConfig.SMALL.respectParagraphs());
        assertTrue(ChunkingConfig.SMALL.enableContextPrefix());
    }

    @Test
    void LARGE常量值正确() {
        assertEquals(2048, ChunkingConfig.LARGE.maxChunkSize());
        assertEquals(200, ChunkingConfig.LARGE.minChunkSize());
        assertEquals(256, ChunkingConfig.LARGE.overlapSize());
        assertEquals(1024, ChunkingConfig.LARGE.maxChunkTokens());
        assertTrue(ChunkingConfig.LARGE.respectSentences());
        assertTrue(ChunkingConfig.LARGE.respectParagraphs());
        assertTrue(ChunkingConfig.LARGE.enableContextPrefix());
    }
}
