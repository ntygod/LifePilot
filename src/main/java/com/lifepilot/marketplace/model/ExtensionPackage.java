package com.lifepilot.marketplace.model;

import lombok.Builder;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 远程扩展包元数据 — 来自索引 JSON。
 *
 * @param id                  包唯一标识
 * @param name                扩展名称
 * @param type                扩展类型（SKILL / AGENT / WORKFLOW）
 * @param version             版本号（SemVer）
 * @param author              作者
 * @param description         扩展描述
 * @param repoUrl             Git 仓库 URL
 * @param filePath            文件在仓库中的相对路径
 * @param tags                标签列表
 * @param requirements        前置条件说明列表（纯信息性）
 * @param minLifepilotVersion 最低兼容 ZhiWei 版本
 * @param createdAt           创建时间（ISO 8601）
 * @param updatedAt           更新时间（ISO 8601）
 * @param downloads           下载次数
 * @param verified            是否已验证
 * @param installedVersion    本地已安装版本（运行时附加，不来自索引 JSON）
 * @param installed           是否已安装（运行时附加）
 * @author zsg
 * @since 2026-03-08
 */
@Builder(toBuilder = true)
public record ExtensionPackage(
        String id,
        String name,
        ExtensionType type,
        String version,
        String author,
        String description,
        String repoUrl,
        String filePath,
        List<String> tags,
        List<String> requirements,
        String minLifepilotVersion,
        String createdAt,
        String updatedAt,
        int downloads,
        boolean verified,
        @Nullable String installedVersion,
        boolean installed
) {
    public ExtensionPackage {
        tags = tags != null ? List.copyOf(tags) : List.of();
        requirements = requirements != null ? List.copyOf(requirements) : List.of();
    }
}
