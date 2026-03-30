package com.lifepilot.interaction.model;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 渠道插件资源描述。
 *
 * <p>用于声明 README、图标、示例配置和其他静态资产的相对路径，
 * 供 Marketplace 安装后下载到本地插件目录。</p>
 *
 * @author zsg
 * @since 2026-03-29
 */
public record ChannelPluginResources(
        @Nullable String readmePath,
        @Nullable String iconPath,
        List<String> examplePaths,
        List<String> assetPaths
) {

    public ChannelPluginResources {
        examplePaths = examplePaths != null ? List.copyOf(examplePaths) : List.of();
        assetPaths = assetPaths != null ? List.copyOf(assetPaths) : List.of();
    }

    public List<String> referencedPaths() {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        addIfPresent(paths, readmePath);
        addIfPresent(paths, iconPath);
        examplePaths.forEach(path -> addIfPresent(paths, path));
        assetPaths.forEach(path -> addIfPresent(paths, path));
        return List.copyOf(new ArrayList<>(paths));
    }

    private static void addIfPresent(LinkedHashSet<String> paths, @Nullable String path) {
        if (path != null && !path.isBlank()) {
            paths.add(path);
        }
    }
}
