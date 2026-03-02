package com.lifepilot.interaction.web.model;

/**
 * 附件上传响应。
 *
 * @param fileId   文件 ID
 * @param url      文件访问 URL
 * @param filename 文件名
 * @param size     文件大小（字节）
 * @param type     MIME 类型
 * @author zsg
 * @since 2026-02-28
 */
public record UploadResponse(
        String fileId,
        String url,
        String filename,
        long size,
        String type
) {
}
