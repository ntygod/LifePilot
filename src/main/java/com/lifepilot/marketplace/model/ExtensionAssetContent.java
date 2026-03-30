package com.lifepilot.marketplace.model;

/**
 * 已安装扩展资产内容。
 *
 * <p>用于安全地向 Web 控制面暴露 Marketplace 插件目录内的已登记资源。</p>
 *
 * @param packageId    扩展包 ID
 * @param relativePath 相对插件根目录的路径
 * @param kind         资产类别
 * @param contentType  推断出的内容类型
 * @param content      资源字节内容
 * @author zsg
 * @since 2026-03-29
 */
public record ExtensionAssetContent(
        String packageId,
        String relativePath,
        String kind,
        String contentType,
        byte[] content
) {
}
