package com.lifepilot.notification;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 通知历史记录数据载体。
 *
 * <p>对应 {@code notification_history} 数据库表，记录每条通知的发送详情。
 *
 * @param id           通知唯一标识
 * @param userId       目标用户标识
 * @param typeId       通知类型标识（可选）
 * @param urgency      紧急程度
 * @param contentJson  通知内容 JSON 序列化
 * @param channel      发送渠道（WEB / WECOM / FEISHU / DINGTALK / passive）
 * @param readStatus   已读状态（UNREAD / READ）
 * @param status       发送状态（SENT / FAILED）
 * @param metadataJson 扩展元数据 JSON（可选）
 * @param sentAt       发送时间
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 * @author zsg
 * @since 2026-03-13
 */
public record NotificationRecord(
        String id,
        String userId,
        @Nullable String typeId,
        Urgency urgency,
        String contentJson,
        String channel,
        String readStatus,
        String status,
        @Nullable String metadataJson,
        Instant sentAt,
        Instant createdAt,
        Instant updatedAt
) {}
