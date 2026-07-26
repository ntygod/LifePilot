package com.lifepilot.interaction.web.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * 本地诊断包导出结果。
 *
 * @param createdAt         创建时间
 * @param fileName          诊断包文件名
 * @param path              诊断包绝对路径
 * @param sizeBytes         诊断包大小
 * @param includedFileCount 写入诊断包的文件数量
 * @author zsg
 * @since 2026-07-07
 */
public record DiagnosticBundleInfo(
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant createdAt,
        String fileName,
        String path,
        long sizeBytes,
        int includedFileCount
) {
}
