package com.lifepilot.mcp.protocol;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * MCP 模块共享的 JSON 序列化支持。
 *
 * <p>统一配置 ObjectMapper，避免各类重复创建实例。</p>
 *
 * @author zsg
 * @since 2026-03-22
 */
public final class McpJsonSupport {

    /** MCP 模块共享的 ObjectMapper 实例（线程安全，不可变配置）。 */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private McpJsonSupport() {}
}
