package com.lifepilot.interaction.web.model;

import com.lifepilot.notification.NotificationRecord;
import com.lifepilot.notification.Urgency;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 通知历史 DTO，映射自 {@link NotificationRecord}。
 *
 * @param id           通知唯一标识
 * @param userId       目标用户标识
 * @param typeId       通知类型标识
 * @param urgency      紧急程度
 * @param contentJson  通知内容 JSON
 * @param channel      发送渠道
 * @param readStatus   已读状态（UNREAD / READ）
 * @param status       发送状态（SENT / FAILED）
 * @param metadataJson 扩展元数据 JSON
 * @param sentAt       发送时间
 * @author zsg
 * @since 2026-03-13
 */
public record NotificationDto(
        String id,
        String userId,
        @Nullable String typeId,
        Urgency urgency,
        String contentJson,
        String channel,
        String readStatus,
        String status,
        @Nullable String metadataJson,
        Instant sentAt
) {

    /**
     * 从 {@link NotificationRecord} 转换为 DTO。
     *
     * @param record 通知记录
     * @return 通知 DTO
     */
    public static NotificationDto from(NotificationRecord record) {
        return new NotificationDto(
                record.id(),
                record.userId(),
                record.typeId(),
                record.urgency(),
                record.contentJson(),
                record.channel(),
                record.readStatus(),
                record.status(),
                record.metadataJson(),
                record.sentAt()
        );
    }
}
