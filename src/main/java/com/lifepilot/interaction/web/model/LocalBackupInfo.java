package com.lifepilot.interaction.web.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * 本地数据备份结果。
 *
 * @param createdAt         创建时间
 * @param fileName          备份文件名
 * @param path              备份文件绝对路径
 * @param sizeBytes         备份文件大小
 * @param includedFileCount 写入备份的文件数量
 * @author zsg
 * @since 2026-07-04
 */
public record LocalBackupInfo(
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant createdAt,
        String fileName,
        String path,
        long sizeBytes,
        int includedFileCount
) {
}
