package com.lifepilot.interaction.runtime.model;

import com.lifepilot.interaction.model.DeliveryMode;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 连接器上报入站事件请求。
 *
 * @author zsg
 * @since 2026-03-29
 */
public record ChannelRuntimeEventRequest(
        @Nullable String eventId,
        @Nullable String messageId,
        String userId,
        @Nullable String sessionId,
        Content content,
        List<Attachment> attachments,
        @Nullable Map<String, Object> metadata,
        @Nullable DeliveryHints deliveryHints,
        @Nullable ReplyTarget replyTarget,
        @Nullable Instant occurredAt
) {

    public ChannelRuntimeEventRequest {
        attachments = attachments != null ? List.copyOf(attachments) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : null;
    }

    /**
     * 入站内容描述。
     */
    public record Content(
            @Nullable String type,
            @Nullable String text,
            @Nullable String name,
            @Nullable Map<String, Object> payload
    ) {

        public Content {
            type = type != null && !type.isBlank() ? type.trim() : "text";
            payload = payload != null ? Map.copyOf(payload) : null;
        }
    }

    /**
     * 入站附件。
     */
    public record Attachment(
            @Nullable String attachmentId,
            @Nullable String fileName,
            @Nullable String mimeType,
            String base64Data,
            long size
    ) {
    }

    /**
     * 投递提示。
     */
    public record DeliveryHints(@Nullable DeliveryMode deliveryMode) {
    }

    /**
     * 回复目标。
     */
    public record ReplyTarget(
            @Nullable String userId,
            @Nullable String sessionId,
            @Nullable Map<String, Object> attributes
    ) {

        public ReplyTarget {
            attributes = attributes != null ? Map.copyOf(attributes) : null;
        }
    }
}
