package com.lifepilot.knowledge.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ParseResult record 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class ParseResultTest {

    // --- isEmpty() ---

    @Test
    void isEmpty_text为null时返回true() {
        var result = new ParseResult(null, List.of(), DocumentMetadata.empty(), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void isEmpty_text为空字符串时返回true() {
        var result = new ParseResult("", List.of(), DocumentMetadata.empty(), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void isEmpty_text为纯空白时返回true() {
        var result = new ParseResult("   \t\n  ", List.of(), DocumentMetadata.empty(), List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void isEmpty_text有内容时返回false() {
        var result = new ParseResult("hello", List.of(), DocumentMetadata.empty(), List.of());
        assertFalse(result.isEmpty());
    }

    // --- estimateTokenCount() ---

    @Test
    void estimateTokenCount_空文本返回0() {
        var result = new ParseResult(null, List.of(), DocumentMetadata.empty(), List.of());
        assertEquals(0, result.estimateTokenCount());
    }

    @Test
    void estimateTokenCount_纯英文按空格分词() {
        var result = new ParseResult("hello world foo", List.of(), DocumentMetadata.empty(), List.of());
        assertEquals(3, result.estimateTokenCount());
    }

    @Test
    void estimateTokenCount_纯中文按字符计数() {
        var result = new ParseResult("你好世界", List.of(), DocumentMetadata.empty(), List.of());
        // 4 个中文字符 = 4 token，split("\\s+") 产生 1 个元素，englishWords = 1 - 4 = -3，Math.max(0, -3) = 0
        assertEquals(4, result.estimateTokenCount());
    }

    @Test
    void estimateTokenCount_中英混合() {
        // "hello 你好 world"（14 字符）→ 中文 2 个(2 token) + 其他 12 个 / 4 = 3 token
        // total = 2 + 3 = 5（使用 TextUtils.estimateTokens 字符级估算，修复旧算法 bug）
        var result = new ParseResult("hello 你好 world", List.of(), DocumentMetadata.empty(), List.of());
        assertEquals(5, result.estimateTokenCount());
    }

    @Test
    void estimateTokenCount_空白字符串返回0() {
        var result = new ParseResult("   ", List.of(), DocumentMetadata.empty(), List.of());
        assertEquals(0, result.estimateTokenCount());
    }
}
