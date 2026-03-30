package com.lifepilot.marketplace.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 已安装扩展快照。
 *
 * <p>用于向控制面或前端暴露安装后的入口文件、根目录和资产清单。</p>
 *
 * @param packageId       扩展包 ID
 * @param type            扩展类型
 * @param name            扩展名称
 * @param version         已安装版本
 * @param entryPath       主入口文件路径
 * @param installRootPath 安装根目录
 * @param assets          安装资产列表
 * @author zsg
 * @since 2026-03-29
 */
public record ExtensionInstallation(
        String packageId,
        ExtensionType type,
        String name,
        String version,
        String entryPath,
        String installRootPath,
        @Nullable List<InstalledExtensionAsset> assets
) {
}
