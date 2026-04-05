package com.lifepilot.interaction.web.model;

import com.lifepilot.agent.task.reminder.ReminderFeedbackType;
import com.lifepilot.agent.task.reminder.ReminderNotificationFeedbackView;
import com.lifepilot.notification.NotificationRecord;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 通知历史 DTO，映射自 {@link NotificationRecord}。
 *
 * @param id           通知唯一标识
 * @param userId       目标用户标识
 * @param typeId       通知类型标识
 * @param contentJson  通知内容 JSON
 * @param channel      发送渠道
 * @param readStatus   已读状态（UNREAD / READ）
 * @param status       发送状态（SENT / FAILED）
 * @param metadataJson 扩展元数据 JSON
 * @param sentAt       发送时间
 * @param feedbackType 主动提醒反馈类型
 * @param feedbackComment 主动提醒反馈备注
 * @param feedbackAt   主动提醒反馈时间
 * @param topicMuted   该提醒主题是否已静默
 * @author zsg
 * @since 2026-03-13
 */
public record NotificationDto(
        String id,
        String userId,
        @Nullable String typeId,
        String contentJson,
        String channel,
        String readStatus,
        String status,
        @Nullable String metadataJson,
        Instant sentAt,
        @Nullable ReminderFeedbackType feedbackType,
        @Nullable String feedbackComment,
        @Nullable Instant feedbackAt,
        @Nullable Boolean topicMuted
) {

    /**
     * 从 {@link NotificationRecord} 转换为 DTO。
     *
     * @param record 通知记录
     * @return 通知 DTO
     */
    public static NotificationDto from(NotificationRecord record) {
        return from(record, null);
    }

    /**
     * 从 {@link NotificationRecord} 和主动提醒反馈视图转换为 DTO。
     *
     * @param record       通知记录
     * @param feedbackView 主动提醒反馈视图
     * @return 通知 DTO
     */
    public static NotificationDto from(NotificationRecord record,
                                       @Nullable ReminderNotificationFeedbackView feedbackView) {
        return new NotificationDto(
                record.id(),
                record.userId(),
                record.typeId(),
                record.contentJson(),
                record.channel(),
                record.readStatus(),
                record.status(),
                record.metadataJson(),
                record.sentAt(),
                feedbackView != null ? feedbackView.feedbackType() : null,
                feedbackView != null ? feedbackView.comment() : null,
                feedbackView != null ? feedbackView.feedbackAt() : null,
                feedbackView != null ? feedbackView.topicMuted() : null
        );
    }
}
