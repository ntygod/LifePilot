package com.lifepilot.interaction.web.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Web UI 会话实体。
 *
 * <p>用于前端多会话管理，包含会话标题、摘要、消息数、置顶状态等信息。
 *
 * @param id            会话 ID（UUID）
 * @param title         会话标题
 * @param summary       会话摘要（可选）
 * @param messageCount  消息数量
 * @param isPinned      是否置顶
 * @param archived      是否归档
 * @param lastMessageAt 最近一次消息时间（可选）
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 * @author zsg
 * @since 2026-02-27
 */
public record ChatSession(
        String id,
        String title,
        String summary,
        int messageCount,
        boolean isPinned,
        boolean archived,
        Instant lastMessageAt,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * 创建新会话的工厂方法。
     *
     * @param title 会话标题（可选，默认"新对话"）
     * @return 新创建的会话实例
     */
    public static ChatSession create(String title) {
        Instant now = Instant.now();
        return new ChatSession(
                UUID.randomUUID().toString(),
                title != null && !title.isBlank() ? title : "新对话",
                null,
                0,
                false,
                false,
                null,
                now,
                now
        );
    }

    /**
     * 创建新会话（使用默认标题）。
     *
     * @return 新创建的会话实例
     */
    public static ChatSession create() {
        return create(null);
    }

    /**
     * 使用指定 ID 创建会话（非 Web 渠道使用，sessionId 格式如 feishu:chatId:openId）。
     *
     * @param id    指定的会话 ID
     * @param title 会话标题
     * @return 新创建的会话实例
     */
    public static ChatSession createWithId(String id, String title) {
        Instant now = Instant.now();
        return new ChatSession(
                id,
                title != null && !title.isBlank() ? title : "新对话",
                null,
                0,
                false,
                false,
                null,
                now,
                now
        );
    }
}
