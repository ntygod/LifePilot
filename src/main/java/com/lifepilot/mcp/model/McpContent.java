package com.lifepilot.mcp.model;

import jakarta.annotation.Nullable;

/**
 * MCP 内容项。
 *
 * @param type 内容类型（text / image / resource）
 * @param text 文本内容（type=text 时）
 * @param data Base64 编码数据（type=image 时）
 * @param mimeType MIME 类型
 * @author zsg
 * @since 2026-02-24
 */
public record McpContent(
        String type,
        @Nullable String text,
        @Nullable String data,
        @Nullable String mimeType
) {}
