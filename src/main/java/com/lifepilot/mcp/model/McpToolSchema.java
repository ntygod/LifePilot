package com.lifepilot.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.Nullable;

import java.util.Map;

/**
 * MCP 工具 Schema（来自 tools/list 响应）。
 *
 * <p>使用 {@code @JsonIgnoreProperties} 容忍 MCP 协议新版本新增的字段
 * （如 {@code title}），避免反序列化失败。</p>
 *
 * @param name 工具名称
 * @param description 工具描述
 * @param inputSchema 输入参数 JSON Schema
 * @param annotations 工具注解
 * @author zsg
 * @since 2026-02-24
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpToolSchema(
        String name,
        @Nullable String description,
        @Nullable Map<String, Object> inputSchema,
        @Nullable McpToolAnnotations annotations
) {}
