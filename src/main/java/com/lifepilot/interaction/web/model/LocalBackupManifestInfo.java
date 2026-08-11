package com.lifepilot.interaction.web.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

/**
 * 本地备份包清单。
 *
 * @param formatVersion        备份格式版本
 * @param createdAt            创建时间
 * @param sourceHome           来源 HOME 目录
 * @param includedFileCount    数据文件数量
 * @param excludedTopLevelDirs 已排除的顶层目录
 * @author zsg
 * @since 2026-07-04
 */
public record LocalBackupManifestInfo(
        String formatVersion,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant createdAt,
        String sourceHome,
        int includedFileCount,
        List<String> excludedTopLevelDirs
) {
}
