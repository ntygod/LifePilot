package com.lifepilot.interaction.web.model;

/**
 * 本地备份校验请求。
 *
 * @param fileName 备份文件名
 * @author zsg
 * @since 2026-07-04
 */
public record LocalBackupValidationRequest(
        String fileName
) {
}
