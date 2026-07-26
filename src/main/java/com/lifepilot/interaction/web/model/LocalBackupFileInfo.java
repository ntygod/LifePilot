package com.lifepilot.interaction.web.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * 本地备份文件信息。
 *
 * @param modifiedAt 文件最后修改时间
 * @param fileName   备份文件名
 * @param path       备份文件绝对路径
 * @param sizeBytes  备份文件大小
 * @author zsg
 * @since 2026-07-04
 */
public record LocalBackupFileInfo(
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant modifiedAt,
        String fileName,
        String path,
        long sizeBytes
) {
}
