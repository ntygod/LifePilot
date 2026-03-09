package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 发送消息请求体。
 *
 * @param content   消息文本内容
 * @param sessionId 会话 ID（可为 null，新会话时自动创建）
 * @author zsg
 * @since 2026-02-27
 */
/**
 * 发送消息请求体。
 *
 * @param content           消息文本内容
 * @param sessionId         会话 ID（可为 null，新会话时自动创建）
 * @param attachmentIds     本轮消息关联的附件 ID 列表
 * @param preferredProvider 会话级偏好 LLM Provider（可为 null，使用默认路由）
 * @author zsg
 * @since 2026-02-27
 */
public record ChatRequest(
        String content,
        @Nullable String sessionId,
        /**
         * 本轮消息关联的附件 ID 列表。
         *
         * <p>由前端在发送消息前通过 /chat/messages/upload 上传得到，用于后续多模态路由
         * 和消息历史中的媒体引用。当前阶段后端可选择性消费该字段。</p>
         */
        @Nullable List<String> attachmentIds,
        /**
         * 会话级偏好 LLM Provider。
         *
         * <p>前端可通过设置页指定模型偏好，透传到 AgentLoop 路由时优先使用。
         * 为 null 时使用默认的 phase → scene 映射。</p>
         */
        @Nullable String preferredProvider
) {}
