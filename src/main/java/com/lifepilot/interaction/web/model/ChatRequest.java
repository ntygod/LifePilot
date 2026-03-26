package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 发送消息请求体。
 *
 * @param turnId 当前轮次 ID
 * @param action 当前执行动作
 * @param content 消息文本内容，可为空字符串
 * @param sessionId 会话 ID（可为 null，新会话时自动创建）
 * @param attachmentIds 本轮消息关联的附件 ID 列表
 * @param preferredProvider 会话级偏好 LLM Provider（可为 null，使用默认路由）
 * @author zsg
 * @since 2026-03-25
 */
public record ChatRequest(
        @Nullable String turnId,
        @Nullable ChatTurnAction action,
        String content,
        @Nullable String sessionId,
        @Nullable List<String> attachmentIds,
        @Nullable String preferredProvider
) {
    public ChatRequest {
        turnId = turnId != null && !turnId.isBlank() ? turnId : UUID.randomUUID().toString();
        action = action != null ? action : ChatTurnAction.SEND;
        content = content != null ? content : "";
        attachmentIds = (attachmentIds == null || attachmentIds.isEmpty()) ? null : List.copyOf(attachmentIds);
    }

    public ChatRequest(String content,
                       @Nullable String sessionId,
                       @Nullable List<String> attachmentIds,
                       @Nullable String preferredProvider) {
        this(null, ChatTurnAction.SEND, content, sessionId, attachmentIds, preferredProvider);
    }

    public boolean hasContent() {
        return !content.isBlank();
    }

    public boolean hasAttachments() {
        return attachmentIds != null && !attachmentIds.isEmpty();
    }

    public boolean hasMessagePayload() {
        return hasContent() || hasAttachments();
    }
}
