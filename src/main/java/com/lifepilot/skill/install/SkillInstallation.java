package com.lifepilot.skill.install;

import java.time.Instant;

/**
 * Skill 安装元数据 —— 对应 skills 表一行。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillInstallation(
        String name,
        SkillSourceType sourceType,
        String sourceUri,
        String filePath,
        String version,
        boolean enabled,
        String marketplaceId,
        String checksum,
        Instant installedAt,
        Instant updatedAt,
        Instant lastActivatedAt
) {
    public SkillInstallation {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空");
        if (sourceType == null) throw new IllegalArgumentException("sourceType 不能为空");
        if (filePath == null || filePath.isBlank()) throw new IllegalArgumentException("filePath 不能为空");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version 不能为空");
    }
}
