package com.lifepilot.interaction.web.model;

import java.time.Instant;

/**
 * 会话摘要信息。
 *
 * @param id        会话 ID
 * @param title     会话标题（取首条消息摘要）
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 * @author zsg
 * @since 2026-02-27
 */
public record SessionInfo(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt
) {}
