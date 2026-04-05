package com.lifepilot.marketplace.clawhub;

import org.springframework.lang.Nullable;

/**
 * ClawHub 搜索结果条目。
 *
 * @param slug        Skill 唯一标识（URL 安全的 slug）
 * @param displayName 显示名称
 * @param summary     简要描述
 * @param score       搜索相关性分数
 * @param updatedAt   最后更新时间（Unix 毫秒时间戳）
 * @author zsg
 * @since 2026-04-03
 */
public record ClawHubSearchResult(
        String slug,
        @Nullable String displayName,
        @Nullable String summary,
        double score,
        long updatedAt
) {}
