package com.lifepilot.interaction.web.model;

import com.lifepilot.agent.model.ResumePolicy;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 发送消息请求体。
 *
 * @param content 消息文本内容，可为空字符串
 * @param sessionId 会话 ID（可为 null，新会话时自动创建）
 * @param attachmentIds 本轮消息关联的附件 ID 列表
 * @param preferredProvider 会话级偏好 LLM Provider（可为 null，使用默认路由）
 * @param resumePolicy 失败恢复策略
 * @author zsg
 * @since 2026-02-27
 */
public record ChatRequest(
        String content,
        @Nullable String sessionId,
        @Nullable List<String> attachmentIds,
        @Nullable String preferredProvider,
        @Nullable ResumePolicy resumePolicy
) {
    public ChatRequest {
        content = content != null ? content : "";
        attachmentIds = (attachmentIds == null || attachmentIds.isEmpty()) ? null : List.copyOf(attachmentIds);
    }

    public ChatRequest(String content,
                       @Nullable String sessionId,
                       @Nullable List<String> attachmentIds,
                       @Nullable String preferredProvider) {
        this(content, sessionId, attachmentIds, preferredProvider, null);
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
