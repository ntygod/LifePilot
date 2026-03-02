package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 发送消息请求体。
 *
 * @param content   消息文本内容
 * @param sessionId 会话 ID（可为 null，新会话时自动创建）
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
        @Nullable java.util.List<String> attachmentIds
) {}
