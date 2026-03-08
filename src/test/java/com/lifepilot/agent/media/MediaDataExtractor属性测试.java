package com.lifepilot.agent.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaDataExtractor 属性测试 — 验证 CP-1/CP-2/CP-3 正确性属性。
 *
 * @author zsg
 * @since 2026-03-08
 */
class MediaDataExtractor属性测试 {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MediaDataExtractor extractor = new MediaDataExtractor(objectMapper);

    /**
     * CP-1: 媒体数据完整性 — 提取的 data 与原始 Base64 完全一致。
     */
    @Property(tries = 50)
    void CP1_提取的媒体数据与原始Base64完全一致(@ForAll("largeBase64") String base64) throws Exception {
        String json = objectMapper.writeValueAsString(
                Map.of("screenshot", base64, "url", "https://example.com"));

        var result = extractor.extract("builtin.browser.screenshot", json);

        assertFalse(result.mediaItems().isEmpty(), "应提取到媒体数据");
        assertEquals(base64, result.mediaItems().getFirst().data(),
                "提取的 data 应与原始 Base64 完全一致");
    }

    /**
     * CP-2: 截断隔离性 — sanitizedOutput 不包含原始 Base64 数据。
     */
    @Property(tries = 50)
    void CP2_sanitizedOutput不包含原始Base64数据(@ForAll("largeBase64") String base64) throws Exception {
        String json = objectMapper.writeValueAsString(
                Map.of("screenshot", base64, "url", "https://example.com"));

        var result = extractor.extract("builtin.browser.screenshot", json);

        assertFalse(result.mediaItems().isEmpty());
        assertFalse(result.sanitizedOutput().contains(base64),
                "sanitizedOutput 不应包含原始 Base64 数据");
    }

    /**
     * CP-3: Token 节省性 — 替换后的 screenshot 字段值为固定占位符。
     */
    @Property(tries = 50)
    void CP3_替换后screenshot字段为固定占位符(@ForAll("largeBase64") String base64) throws Exception {
        String json = objectMapper.writeValueAsString(
                Map.of("screenshot", base64, "url", "https://example.com"));

        var result = extractor.extract("builtin.browser.screenshot", json);

        assertFalse(result.mediaItems().isEmpty());
        var sanitizedNode = objectMapper.readTree(result.sanitizedOutput());
        assertEquals(MediaDataExtractor.PLACEHOLDER,
                sanitizedNode.get("screenshot").textValue(),
                "screenshot 字段应替换为占位符");
    }

    /**
     * 生成长度超过 1000 的随机 Base64 字符串。
     */
    @Provide
    Arbitrary<String> largeBase64() {
        return Arbitraries.bytes().array(byte[].class)
                .ofMinSize(800).ofMaxSize(4000)
                .map(bytes -> Base64.getEncoder().encodeToString(bytes));
    }
}
