package com.lifepilot.interaction.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.Builder;

/**
 * 统一网关消息 record，所有通道消息标准化后的不可变表示。
 *
 * <p>紧凑构造器为 {@code messageId} 和 {@code timestamp} 提供默认值，
 * 集合字段使用 {@link List#copyOf} 和 {@link Map#copyOf} 确保不可变性。
 *
 * @param messageId       消息唯一标识，默认 UUID
 * @param channelType     通道类型
 * @param userId          用户标识
 * @param sessionId       会话标识
 * @param content         消息内容
 * @param attachments     附件列表（不可变）
 * @param channelMetadata 通道元数据
 * @param timestamp       消息时间戳，默认当前时间
 * @param traceHeaders    追踪头信息（不可变）
 * @author zsg
 * @since 2026-02-25
 */
@Builder(toBuilder = true)
public record GatewayMessage(
        String messageId,
        ChannelType channelType,
        String userId,
        String sessionId,
        MessageContent content,
        List<Attachment> attachments,
        ChannelMetadata channelMetadata,
        Instant timestamp,
        Map<String, String> traceHeaders
) {

    /**
     * 紧凑构造器：为 messageId 和 timestamp 提供默认值，集合字段防御性拷贝。
     */
    public GatewayMessage {
        messageId = messageId != null ? messageId : UUID.randomUUID().toString();
        timestamp = timestamp != null ? timestamp : Instant.now();
        attachments = attachments != null ? List.copyOf(attachments) : List.of();
        traceHeaders = traceHeaders != null ? Map.copyOf(traceHeaders) : Map.of();
    }

    /**
     * 返回消息内容的纯文本表示。
     *
     * @return 纯文本字符串
     */
    public String contentAsText() {
        return content.toPlainText();
    }

    /**
     * 判断消息是否为命令消息。
     *
     * @return 如果内容为 {@link MessageContent.CommandMessage} 则返回 true
     */
    public boolean isCommand() {
        return content instanceof MessageContent.CommandMessage;
    }

    /**
     * 判断消息是否为事件消息。
     *
     * @return 如果内容为 {@link MessageContent.EventMessage} 则返回 true
     */
    public boolean isEvent() {
        return content instanceof MessageContent.EventMessage;
    }

    /**
     * 消息附件。
     *
     * @param attachmentId 附件唯一标识
     * @param fileName     文件名
     * @param mimeType     MIME 类型
     * @param data         文件数据
     * @param size         文件大小（字节）
     * @author zsg
     * @since 2026-02-25
     */
    public record Attachment(
            String attachmentId,
            String fileName,
            String mimeType,
            byte[] data,
            long size
    ) {}
}
