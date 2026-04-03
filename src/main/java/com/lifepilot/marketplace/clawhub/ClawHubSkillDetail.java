package com.lifepilot.marketplace.clawhub;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * ClawHub Skill 详情 — 对应 {@code GET /api/v1/skills/{slug}} 的响应。
 *
 * @param slug           Skill 唯一标识
 * @param displayName    显示名称
 * @param summary        简要描述
 * @param version        最新版本号（SemVer）
 * @param downloads      下载次数
 * @param stars          收藏次数
 * @param os             支持的操作系统列表
 * @param ownerHandle    作者标识
 * @param ownerName      作者显示名称
 * @param createdAt      创建时间（Unix 毫秒时间戳）
 * @param updatedAt      更新时间（Unix 毫秒时间戳）
 * @param changelog      最新版本变更记录
 * @author zsg
 * @since 2026-04-03
 */
public record ClawHubSkillDetail(
        String slug,
        @Nullable String displayName,
        @Nullable String summary,
        @Nullable String version,
        int downloads,
        int stars,
        @Nullable List<String> os,
        @Nullable String ownerHandle,
        @Nullable String ownerName,
        long createdAt,
        long updatedAt,
        @Nullable String changelog
) {}
