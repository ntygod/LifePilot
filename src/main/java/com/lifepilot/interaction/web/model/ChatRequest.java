package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 发送消息请求体。
 *
 * @param turnId              当前轮次 ID
 * @param action              当前执行动作
 * @param content             消息文本内容，可为空字符串
 * @param sessionId           会话 ID（可为 null，新会话时自动创建）
 * @param attachmentIds       本轮消息关联的附件 ID 列表
 * @param preferredProvider   会话级偏好 LLM Provider（可为 null，使用默认路由）
 * @param singleTurnOverride  单轮临时覆盖的会话配置（可为 null，为空时走持久化配置）
 * @param visibleContent      重放时应保存/展示给用户的文本；为空时使用 content
 * @param recoveryAction      点击继续/重启等 UI 动作时携带的结构化恢复上下文
 * @author zsg
 * @since 2026-03-25
 */
public record ChatRequest(
        @Nullable String turnId,
        @Nullable ChatTurnAction action,
        String content,
        @Nullable String sessionId,
        @Nullable List<String> attachmentIds,
        @Nullable String preferredProvider,
        @Nullable SessionConfigOverride singleTurnOverride,
        @Nullable String visibleContent,
        @Nullable TurnRecoveryActionRequest recoveryAction
) {
    public ChatRequest {
        turnId = turnId != null && !turnId.isBlank() ? turnId : UUID.randomUUID().toString();
        action = action != null ? action : ChatTurnAction.SEND;
        content = content != null ? content : "";
        attachmentIds = (attachmentIds == null || attachmentIds.isEmpty()) ? null : List.copyOf(attachmentIds);
        if (singleTurnOverride != null && singleTurnOverride.isEmpty()) {
            singleTurnOverride = null;
        }
        visibleContent = visibleContent != null && !visibleContent.isBlank() ? visibleContent : null;
        if (recoveryAction != null && recoveryAction.isEmpty()) {
            recoveryAction = null;
        }
    }

    public ChatRequest(String content,
                       @Nullable String sessionId,
                       @Nullable List<String> attachmentIds,
                       @Nullable String preferredProvider) {
        this(null, ChatTurnAction.SEND, content, sessionId, attachmentIds, preferredProvider, null, null, null);
    }

    public ChatRequest(@Nullable String turnId,
                       @Nullable ChatTurnAction action,
                       String content,
                       @Nullable String sessionId,
                       @Nullable List<String> attachmentIds,
                       @Nullable String preferredProvider) {
        this(turnId, action, content, sessionId, attachmentIds, preferredProvider, null, null, null);
    }

    public ChatRequest(@Nullable String turnId,
                       @Nullable ChatTurnAction action,
                       String content,
                       @Nullable String sessionId,
                       @Nullable List<String> attachmentIds,
                       @Nullable String preferredProvider,
                       @Nullable SessionConfigOverride singleTurnOverride) {
        this(turnId, action, content, sessionId, attachmentIds, preferredProvider, singleTurnOverride, null, null);
    }

    public ChatRequest(@Nullable String turnId,
                       @Nullable ChatTurnAction action,
                       String content,
                       @Nullable String sessionId,
                       @Nullable List<String> attachmentIds,
                       @Nullable String preferredProvider,
                       @Nullable SessionConfigOverride singleTurnOverride,
                       @Nullable String visibleContent) {
        this(turnId, action, content, sessionId, attachmentIds, preferredProvider, singleTurnOverride, visibleContent, null);
    }

    public boolean hasContent() {
        return !content.isBlank();
    }

    public String visibleContentOrContent() {
        return visibleContent != null ? visibleContent : content;
    }

    public boolean hasAttachments() {
        return attachmentIds != null && !attachmentIds.isEmpty();
    }

    public boolean hasMessagePayload() {
        return hasContent() || hasAttachments();
    }
}
