package com.lifepilot.interaction.web.model;

import java.time.Instant;

/**
 * 会话摘要信息。
 *
 * @param id               会话 ID
 * @param title            会话标题（取首条消息摘要）
 * @param createdAt        创建时间
 * @param updatedAt        最后更新时间
 * @param pinned           是否置顶
 * @param archived         是否归档
 * @param lastMessagePreview 最近消息预览
 * @param lastMessageAt    最近一次消息时间
 * @author zsg
 * @since 2026-02-27
 */
public record SessionInfo(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        Boolean pinned,
        Boolean archived,
        String lastMessagePreview,
        Instant lastMessageAt
) {}
