package com.lifepilot.interaction.web.model;

import java.time.Instant;
import java.util.List;

/**
 * 会话详细信息。
 *
 * @param id                会话 ID
 * @param title             会话标题
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 * @param pinned            是否置顶
 * @param archived          是否归档
 * @param knowledgeBaseIds  关联的知识库 ID 列表
 * @param messageCount      消息数量
 * @param totalTokens       总 Token 数
 * @param lastMessagePreview 最后一条消息预览
 */
public record SessionDetailInfo(
        String id,
        String title,
        Instant createdAt,
        Instant updatedAt,
        Boolean pinned,
        Boolean archived,
        List<String> knowledgeBaseIds,
        Integer messageCount,
        Long totalTokens,
        String lastMessagePreview
) {}
