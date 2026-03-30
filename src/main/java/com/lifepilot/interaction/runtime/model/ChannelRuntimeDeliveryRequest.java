package com.lifepilot.interaction.runtime.model;

import com.lifepilot.interaction.model.DeliveryMode;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 渠道统一出站请求。
 *
 * @author zsg
 * @since 2026-03-29
 */
public record ChannelRuntimeDeliveryRequest(
        String instanceId,
        String responseId,
        DeliveryMode deliveryMode,
        Target target,
        Content content,
        List<Attachment> attachments,
        Map<String, Object> metadata
) {

    public ChannelRuntimeDeliveryRequest {
        attachments = attachments != null ? List.copyOf(attachments) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * 回复目标。
     */
    public record Target(
            @Nullable String userId,
            @Nullable String sessionId,
            Map<String, Object> attributes
    ) {

        public Target {
            attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
        }
    }

    /**
     * 出站内容。
     */
    public record Content(
            String type,
            String plainText,
            Map<String, Object> payload
    ) {

        public Content {
            payload = payload != null ? Map.copyOf(payload) : Map.of();
        }
    }

    /**
     * 出站附件。
     */
    public record Attachment(
            String attachmentId,
            @Nullable String fileName,
            @Nullable String mimeType,
            String base64Data,
            long size
    ) {
    }
}
