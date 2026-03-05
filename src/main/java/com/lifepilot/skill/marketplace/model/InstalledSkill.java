package com.lifepilot.skill.marketplace.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 已安装的市场 Skill 记录 — 持久化到 installed_skills 表。
 *
 * @param id                 记录 UUID
 * @param packageId          市场包 ID
 * @param name               Skill 名称
 * @param version            已安装版本号
 * @param indexSourceUrl     索引源 URL
 * @param repoUrl            Git 仓库 URL
 * @param filePath           YAML 文件路径
 * @param securityReportJson 安全报告 JSON（可空）
 * @param createdAt          安装时间
 * @param updatedAt          最后更新时间
 * @author zsg
 * @since 2026-03-05
 */
public record InstalledSkill(
        String id,
        String packageId,
        String name,
        String version,
        String indexSourceUrl,
        String repoUrl,
        String filePath,
        @Nullable String securityReportJson,
        Instant createdAt,
        Instant updatedAt
) {}
