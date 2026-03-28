package com.lifepilot.agent.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaDataExtractor 单元测试。
 *
 * @author zsg
 * @since 2026-03-08
 */
class MediaDataExtractorTest {

    private MediaDataExtractor extractor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        extractor = new MediaDataExtractor(objectMapper);
    }

    @Test
    void 包含截图字段时_提取媒体数据并替换为占位符() throws Exception {
        // 生成超过 1000 字符的 Base64 数据
        String base64 = "a".repeat(2000);
        String json = objectMapper.writeValueAsString(
                java.util.Map.of("screenshot", base64, "url", "https://example.com", "fullPage", false));

        var result = extractor.extract("browser.screenshot", json);

        assertEquals(1, result.mediaItems().size());
        var item = result.mediaItems().getFirst();
        assertEquals("image/png", item.mediaType());
        assertEquals("base64", item.encoding());
        assertEquals(base64, item.data());
        assertEquals("screenshot", item.fieldName());
        assertEquals("https://example.com", item.metadata().get("url"));

        // sanitizedOutput 中 screenshot 字段应为占位符
        var sanitizedNode = objectMapper.readTree(result.sanitizedOutput());
        assertEquals(MediaDataExtractor.PLACEHOLDER, sanitizedNode.get("screenshot").textValue());
    }

    @Test
    void 无媒体字段时_返回原始输出() throws Exception {
        String json = objectMapper.writeValueAsString(
                java.util.Map.of("result", "success", "count", 42));

        var result = extractor.extract("some.tool", json);

        assertTrue(result.mediaItems().isEmpty());
        assertEquals(json, result.sanitizedOutput());
    }

    @Test
    void 截图字段长度低于阈值时_不提取() throws Exception {
        String shortBase64 = "abc123";
        String json = objectMapper.writeValueAsString(
                java.util.Map.of("screenshot", shortBase64, "url", "https://example.com"));

        var result = extractor.extract("browser.screenshot", json);

        assertTrue(result.mediaItems().isEmpty());
        assertEquals(json, result.sanitizedOutput());
    }

    @Test
    void 空输入时_返回空列表() {
        var result = extractor.extract("tool", null);
        assertNull(result.sanitizedOutput());
        assertTrue(result.mediaItems().isEmpty());

        var result2 = extractor.extract("tool", "");
        assertEquals("", result2.sanitizedOutput());
        assertTrue(result2.mediaItems().isEmpty());

        var result3 = extractor.extract("tool", "   ");
        assertEquals("   ", result3.sanitizedOutput());
        assertTrue(result3.mediaItems().isEmpty());
    }

    @Test
    void 非JSON输入时_返回原始输出() {
        String notJson = "this is not json";
        var result = extractor.extract("tool", notJson);

        assertEquals(notJson, result.sanitizedOutput());
        assertTrue(result.mediaItems().isEmpty());
    }

    @Test
    void JSON数组输入时_返回原始输出() {
        String jsonArray = "[1, 2, 3]";
        var result = extractor.extract("tool", jsonArray);

        assertEquals(jsonArray, result.sanitizedOutput());
        assertTrue(result.mediaItems().isEmpty());
    }

    @Test
    void metadata包含非媒体字段() throws Exception {
        String base64 = "x".repeat(1500);
        String json = objectMapper.writeValueAsString(
                java.util.Map.of("screenshot", base64, "url", "https://test.com", "fullPage", true));

        var result = extractor.extract("browser.screenshot", json);

        assertEquals(1, result.mediaItems().size());
        var metadata = result.mediaItems().getFirst().metadata();
        assertEquals("https://test.com", metadata.get("url"));
        assertEquals(true, metadata.get("fullPage"));
        assertFalse(metadata.containsKey("screenshot"));
    }
}
