package com.lifepilot.llm.multimodal;

import java.util.Map;
import java.util.Objects;

import org.springframework.lang.Nullable;

/**
 * 媒体内容载体。
 * 封装图片、文档或音频的二进制数据及元信息。
 *
 * @author zsg
 * @since 2026-07-01
 */
public record MediaContent(
    String id,
    String mimeType,
    byte[] data,
    @Nullable String fileName,
    long sizeBytes,
    Map<String, String> metadata
) {
    public MediaContent {
        Objects.requireNonNull(id, "媒体 ID 不能为空");
        Objects.requireNonNull(mimeType, "MIME 类型不能为空");
        Objects.requireNonNull(data, "媒体数据不能为空");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
