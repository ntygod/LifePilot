package com.lifepilot.mcp.cache;

import com.lifepilot.mcp.model.McpToolAnnotations;
import com.lifepilot.mcp.model.McpToolSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 工具清单缓存测试。
 *
 * <p>使用 {@code @TempDir} 隔离文件系统操作，
 * 通过包可见构造函数 {@code McpToolManifestCache(Path)} 注入临时目录。</p>
 *
 * @author zsg
 * @since 2026-04-09
 */
class McpToolManifestCacheTest {

    @TempDir
    Path tempDir;

    private McpToolManifestCache cache;

    @BeforeEach
    void 初始化() {
        cache = new McpToolManifestCache(tempDir);
    }

    // ─────────────────────────────────────────────
    //  load 测试
    // ─────────────────────────────────────────────

    @Test
    void load_无缓存文件_返回空列表() {
        List<McpToolSchema> result = cache.load("non-existent-server");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void load_文件损坏_返回空列表() throws IOException {
        Path file = tempDir.resolve("broken-server.json");
        Files.writeString(file, "{ 这不是合法的 JSON !!!");

        List<McpToolSchema> result = cache.load("broken-server");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void load_空数组文件_返回空列表() throws IOException {
        Path file = tempDir.resolve("empty-server.json");
        Files.writeString(file, "[]");

        List<McpToolSchema> result = cache.load("empty-server");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // ─────────────────────────────────────────────
    //  save 测试
    // ─────────────────────────────────────────────

    @Test
    void save_创建文件() {
        var schemas = List.of(
                new McpToolSchema("tool-a", "工具A", Map.of("type", "object"), null)
        );

        cache.save("my-server", schemas);

        Path file = tempDir.resolve("my-server.json");
        assertTrue(Files.exists(file), "缓存文件应被创建");
    }

    @Test
    void save_目录不存在时自动创建() {
        Path nestedDir = tempDir.resolve("sub").resolve("deep");
        var nestedCache = new McpToolManifestCache(nestedDir);

        var schemas = List.of(
                new McpToolSchema("tool-b", "工具B", null, null)
        );

        assertDoesNotThrow(() -> nestedCache.save("server-x", schemas));
        assertTrue(Files.exists(nestedDir.resolve("server-x.json")));
    }

    // ─────────────────────────────────────────────
    //  save + load 往返测试
    // ─────────────────────────────────────────────

    @Test
    void save后load_返回相同数据() {
        var schemas = List.of(
                new McpToolSchema("read-file", "读取文件内容",
                        Map.of("type", "object", "properties", Map.of(
                                "path", Map.of("type", "string")
                        )),
                        null),
                new McpToolSchema("write-file", "写入文件内容",
                        Map.of("type", "object"),
                        new McpToolAnnotations(null, false, true, false, false))
        );

        cache.save("fs-server", schemas);
        List<McpToolSchema> loaded = cache.load("fs-server");

        assertEquals(2, loaded.size());
        assertEquals("read-file", loaded.get(0).name());
        assertEquals("读取文件内容", loaded.get(0).description());
        assertNotNull(loaded.get(0).inputSchema());
        assertEquals("write-file", loaded.get(1).name());
        assertNotNull(loaded.get(1).annotations());
        assertEquals(true, loaded.get(1).annotations().destructiveHint());
    }

    @Test
    void save后load_schema为null的字段保持null() {
        var schemas = List.of(
                new McpToolSchema("minimal", null, null, null)
        );

        cache.save("minimal-server", schemas);
        List<McpToolSchema> loaded = cache.load("minimal-server");

        assertEquals(1, loaded.size());
        assertEquals("minimal", loaded.get(0).name());
        assertNull(loaded.get(0).description());
        assertNull(loaded.get(0).inputSchema());
        assertNull(loaded.get(0).annotations());
    }

    @Test
    void save_覆盖已有缓存() {
        var original = List.of(
                new McpToolSchema("old-tool", "旧工具", null, null)
        );
        cache.save("overwrite-server", original);

        var updated = List.of(
                new McpToolSchema("new-tool-1", "新工具1", null, null),
                new McpToolSchema("new-tool-2", "新工具2", null, null)
        );
        cache.save("overwrite-server", updated);

        List<McpToolSchema> loaded = cache.load("overwrite-server");
        assertEquals(2, loaded.size());
        assertEquals("new-tool-1", loaded.get(0).name());
        assertEquals("new-tool-2", loaded.get(1).name());
    }

    // ─────────────────────────────────────────────
    //  evict 测试
    // ─────────────────────────────────────────────

    @Test
    void evict_删除缓存文件() {
        cache.save("to-evict", List.of(
                new McpToolSchema("tool", "描述", null, null)
        ));
        Path file = tempDir.resolve("to-evict.json");
        assertTrue(Files.exists(file), "前置条件：缓存文件应存在");

        cache.evict("to-evict");

        assertFalse(Files.exists(file), "缓存文件应被删除");
    }

    @Test
    void evict_文件不存在_不报错() {
        assertDoesNotThrow(() -> cache.evict("never-saved"));
    }

    @Test
    void evict后load_返回空列表() {
        cache.save("evict-then-load", List.of(
                new McpToolSchema("tool", "描述", null, null)
        ));
        cache.evict("evict-then-load");

        List<McpToolSchema> result = cache.load("evict-then-load");
        assertTrue(result.isEmpty());
    }
}
