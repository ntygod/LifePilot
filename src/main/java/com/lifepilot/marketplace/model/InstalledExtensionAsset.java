package com.lifepilot.marketplace.model;

/**
 * 已安装扩展资产记录。
 *
 * <p>描述插件安装后落盘的静态资源，便于后续控制面或前端按类型消费。</p>
 *
 * @param kind         资产类别，如 README / ICON / EXAMPLE / ASSET
 * @param relativePath 相对插件根目录的资源路径
 * @param localPath    本地绝对路径
 * @author zsg
 * @since 2026-03-29
 */
public record InstalledExtensionAsset(
        String kind,
        String relativePath,
        String localPath
) {
}
