package com.lifepilot.mcp.config;

import com.lifepilot.mcp.transport.TransportType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * McpServerConfig 单元测试。
 *
 * @author zsg
 * @since 2026-02-24
 */
class McpServerConfigTest {

    @Test
    void stdio配置_缺少command_抛出异常() {
        assertThrows(IllegalArgumentException.class, () ->
                McpServerConfig.builder()
                        .name("test")
                        .transport(TransportType.STDIO)
                        .command(null)
                        .build());
    }

    @Test
    void streamableHttp配置_缺少url_抛出异常() {
        assertThrows(IllegalArgumentException.class, () ->
                McpServerConfig.builder()
                        .name("test")
                        .transport(TransportType.STREAMABLE_HTTP)
                        .url(null)
                        .build());
    }

    @Test
    void sseLegacy配置_缺少url_抛出异常() {
        assertThrows(IllegalArgumentException.class, () ->
                McpServerConfig.builder()
                        .name("test")
                        .transport(TransportType.SSE_LEGACY)
                        .url(null)
                        .build());
    }

    @Test
    void 名称为空_抛出异常() {
        assertThrows(IllegalArgumentException.class, () ->
                McpServerConfig.builder()
                        .name("")
                        .transport(TransportType.STDIO)
                        .command("npx")
                        .build());
    }

    @Test
    void 默认值填充_timeout() {
        var config = McpServerConfig.builder()
                .name("test")
                .transport(TransportType.STDIO)
                .command("npx")
                .build();

        assertEquals(Duration.ofSeconds(60), config.timeout());
        assertEquals(Duration.ofMillis(500), config.reconnectDelay());
        assertEquals(5, config.maxReconnectAttempts());
        assertEquals(Duration.ofSeconds(30), config.healthCheckInterval());
        assertEquals(List.of(), config.args());
    }

    @Test
    void stdio配置_正常创建() {
        var config = McpServerConfig.builder()
                .name("filesystem")
                .transport(TransportType.STDIO)
                .command("npx")
                .args(List.of("-y", "@modelcontextprotocol/server-filesystem"))
                .autoConnect(true)
                .reconnect(true)
                .build();

        assertEquals("filesystem", config.name());
        assertEquals(TransportType.STDIO, config.transport());
        assertEquals("npx", config.command());
        assertEquals(2, config.args().size());
        assertTrue(config.autoConnect());
    }

    @Test
    void streamableHttp配置_正常创建() {
        var config = McpServerConfig.builder()
                .name("github")
                .transport(TransportType.STREAMABLE_HTTP)
                .url("http://localhost:3001/mcp")
                .timeout(Duration.ofSeconds(30))
                .build();

        assertEquals("github", config.name());
        assertEquals(TransportType.STREAMABLE_HTTP, config.transport());
        assertEquals("http://localhost:3001/mcp", config.url());
        assertEquals(Duration.ofSeconds(30), config.timeout());
    }
}
