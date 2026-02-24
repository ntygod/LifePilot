package com.lifepilot.mcp.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonRpcMessage 单元测试。
 *
 * @author zsg
 * @since 2026-02-24
 */
class JsonRpcMessageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void request_工厂方法正确设置字段() {
        var msg = JsonRpcMessage.request(1L, "tools/list", Map.of("key", "value"));

        assertEquals("2.0", msg.jsonrpc());
        assertEquals(1L, msg.id());
        assertEquals("tools/list", msg.method());
        assertEquals(Map.of("key", "value"), msg.params());
        assertNull(msg.result());
        assertNull(msg.error());
    }

    @Test
    void notification_工厂方法id为null() {
        var msg = JsonRpcMessage.notification("notifications/initialized", Map.of());

        assertEquals("2.0", msg.jsonrpc());
        assertNull(msg.id());
        assertEquals("notifications/initialized", msg.method());
        assertNull(msg.result());
        assertNull(msg.error());
    }

    @Test
    void response_工厂方法正确设置字段() {
        var result = Map.of("tools", "[]");
        var msg = JsonRpcMessage.response(42L, result);

        assertEquals("2.0", msg.jsonrpc());
        assertEquals(42L, msg.id());
        assertNull(msg.method());
        assertNull(msg.params());
        assertEquals(result, msg.result());
        assertNull(msg.error());
    }

    @Test
    void request_序列化反序列化往返属性() throws Exception {
        var original = JsonRpcMessage.request(1L, "tools/call",
                Map.of("name", "read_file", "arguments", Map.of("path", "/tmp")));

        String json = MAPPER.writeValueAsString(original);
        var deserialized = MAPPER.readValue(json, JsonRpcMessage.class);

        assertEquals(original.jsonrpc(), deserialized.jsonrpc());
        assertEquals(original.id(), deserialized.id());
        assertEquals(original.method(), deserialized.method());
        assertNotNull(deserialized.params());
        assertNull(deserialized.result());
        assertNull(deserialized.error());
    }

    @Test
    void notification_序列化不包含null字段() throws Exception {
        var msg = JsonRpcMessage.notification("notifications/initialized", Map.of());
        String json = MAPPER.writeValueAsString(msg);

        assertFalse(json.contains("\"id\""));
        assertFalse(json.contains("\"result\""));
        assertFalse(json.contains("\"error\""));
        assertTrue(json.contains("\"jsonrpc\""));
        assertTrue(json.contains("\"method\""));
    }

    @Test
    void response_序列化反序列化往返属性() throws Exception {
        var original = JsonRpcMessage.response(99L, Map.of("status", "ok"));

        String json = MAPPER.writeValueAsString(original);
        var deserialized = MAPPER.readValue(json, JsonRpcMessage.class);

        assertEquals(original.jsonrpc(), deserialized.jsonrpc());
        assertEquals(original.id(), deserialized.id());
        assertNull(deserialized.method());
        assertNotNull(deserialized.result());
    }
}
