package com.lifepilot.skill.marketplace.model;

import lombok.Builder;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 远程 Skill 包元数据 — 来自索引 JSON。
 *
 * @param id                  包唯一标识
 * @param name                Skill 名称
 * @param description         Skill 描述
 * @param version             版本号（SemVer）
 * @param author              作者
 * @param repoUrl             Git 仓库 URL
 * @param filePath            YAML 文件在仓库中的相对路径
 * @param tags                标签列表
 * @param minLifepilotVersion 最低兼容 LifePilot 版本
 * @param createdAt           创建时间（ISO 8601）
 * @param updatedAt           更新时间（ISO 8601）
 * @param downloads           下载次数
 * @param verified            是否已验证
 * @param installedVersion    本地已安装版本（运行时附加，不来自索引 JSON）
 * @param installed           是否已安装（运行时附加）
 * @author zsg
 * @since 2026-03-05
 */
@Builder(toBuilder = true)
public record SkillPackage(
        String id,
        String name,
        String description,
        String version,
        String author,
        String repoUrl,
        String filePath,
        List<String> tags,
        String minLifepilotVersion,
        String createdAt,
        String updatedAt,
        int downloads,
        boolean verified,
        @Nullable String installedVersion,
        boolean installed
) {
    public SkillPackage {
        tags = tags != null ? List.copyOf(tags) : List.of();
    }
}
