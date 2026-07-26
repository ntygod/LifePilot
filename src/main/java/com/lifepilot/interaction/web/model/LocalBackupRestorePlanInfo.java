package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * 本地备份恢复前预检信息。
 *
 * @param restoreMode             恢复方式
 * @param manualRestoreOnly       是否只提供手动恢复，不自动覆盖 HOME
 * @param includedTopLevelItems   备份包含的顶层数据项
 * @param excludedTopLevelDirs    备份时排除的顶层目录
 * @param targetHome              当前目标 HOME 目录
 * @param currentHomeHasData      当前 HOME 是否已有核心数据
 * @param currentHomeFileCount    当前 HOME 核心数据文件数
 * @param backupSourceHome        备份来源 HOME 目录
 * @param backupIncludedFileCount 备份包含的数据文件数
 * @param restoreStagingDirectory 恢复准备目录
 * @param backupSizeBytes         备份 zip 文件大小
 * @param estimatedRestoreBytes   预计解压后新增占用
 * @param targetUsableBytes       目标磁盘当前可用空间，无法读取时为 -1
 * @param restoreSpaceStatus      空间预检状态：OK / WARN / UNKNOWN
 * @param warnings                恢复前风险提示
 * @param requiredSteps           建议恢复步骤
 * @author zsg
 * @since 2026-07-04
 */
public record LocalBackupRestorePlanInfo(
        String restoreMode,
        boolean manualRestoreOnly,
        List<String> includedTopLevelItems,
        List<String> excludedTopLevelDirs,
        String targetHome,
        boolean currentHomeHasData,
        int currentHomeFileCount,
        String backupSourceHome,
        int backupIncludedFileCount,
        String restoreStagingDirectory,
        long backupSizeBytes,
        long estimatedRestoreBytes,
        long targetUsableBytes,
        String restoreSpaceStatus,
        List<String> warnings,
        List<String> requiredSteps
) {
}
