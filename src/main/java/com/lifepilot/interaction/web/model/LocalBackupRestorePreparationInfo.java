package com.lifepilot.interaction.web.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

/**
 * 本地备份恢复准备结果。
 *
 * @param preparedAt          准备时间
 * @param fileName            备份文件名
 * @param restoreDirectory    安全解压后的恢复准备目录
 * @param extractedFileCount  解压文件数量
 * @param extractedBytes      解压后文件总大小
 * @param warnings            恢复风险提示
 * @param nextSteps           下一步操作建议
 * @author zsg
 * @since 2026-07-07
 */
public record LocalBackupRestorePreparationInfo(
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant preparedAt,
        String fileName,
        String restoreDirectory,
        int extractedFileCount,
        long extractedBytes,
        List<String> warnings,
        List<String> nextSteps
) {
}
