package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 本地备份校验结果。
 *
 * @param fileName        备份文件名
 * @param path            备份文件绝对路径
 * @param status          状态：OK / WARN / ERROR
 * @param detail          校验说明
 * @param sizeBytes       文件大小
 * @param entryCount      zip 内文件条目数
 * @param manifestPresent 是否包含备份清单
 * @param manifest        备份清单
 * @param problems        校验发现的问题
 * @param restorePlan     恢复前预检信息
 * @author zsg
 * @since 2026-07-04
 */
public record LocalBackupValidationInfo(
        String fileName,
        String path,
        String status,
        String detail,
        long sizeBytes,
        int entryCount,
        boolean manifestPresent,
        LocalBackupManifestInfo manifest,
        List<String> problems,
        LocalBackupRestorePlanInfo restorePlan
) {
}
