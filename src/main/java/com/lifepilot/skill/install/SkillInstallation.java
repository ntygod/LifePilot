package com.lifepilot.skill.install;

import java.time.Instant;

/**
 * Skill 安装元数据 —— 对应 skills 表一行。
 *
 * @param filePath Skill 根目录的<strong>绝对规范化路径</strong>（{@link java.nio.file.Path#toAbsolutePath()}
 *                 + {@link java.nio.file.Path#normalize()} 后的 String 形式）。
 *                 {@link com.lifepilot.skill.activation.SkillActivator} 会直接拼接子目录占位符
 *                 （{@code filePath + "/references"} 等），因此必须保证不带相对前缀。
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
