package com.lifepilot.marketplace.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 已安装的市场扩展记录 — 持久化到 installed_extensions 表。
 *
 * @param id                 记录 UUID
 * @param packageId          市场包 ID
 * @param type               扩展类型
 * @param name               扩展名称
 * @param version            已安装版本号
 * @param indexSourceUrl     索引源 URL
 * @param repoUrl            Git 仓库 URL
 * @param filePath           主入口文件路径
 * @param installRootPath    安装根目录
 * @param requirementsJson   前置条件 JSON（可空）
 * @param securityReportJson 安全报告 JSON（可空）
 * @param assetsJson         安装资产 JSON（可空）
 * @param createdAt          安装时间
 * @param updatedAt          最后更新时间
 * @author zsg
 * @since 2026-03-08
 */
public record InstalledExtension(
        String id,
        String packageId,
        ExtensionType type,
        String name,
        String version,
        String indexSourceUrl,
        String repoUrl,
        String filePath,
        String installRootPath,
        @Nullable String requirementsJson,
        @Nullable String securityReportJson,
        @Nullable String assetsJson,
        Instant createdAt,
        Instant updatedAt
) {
    public String entryPath() {
        return filePath;
    }
}
