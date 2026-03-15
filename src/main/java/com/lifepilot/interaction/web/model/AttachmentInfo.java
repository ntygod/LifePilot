package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 附件摘要信息，用于 API 响应。
 *
 * @param id       附件 ID
 * @param fileName 文件名
 * @param fileSize 文件大小（字节）
 * @param mimeType MIME 类型
 * @param url      文件访问 URL（可为 null）
 * @author zsg
 * @since 2026-03-15
 */
public record AttachmentInfo(
        String id,
        String fileName,
        long fileSize,
        String mimeType,
        @Nullable String url
) {}
