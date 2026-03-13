package com.lifepilot.notification;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 被动通知队列条目数据载体。
 *
 * <p>对应 {@code passive_notification_queue} 数据库表，
 * 记录 LOW 紧急度通知的入队和投递状态。
 *
 * @param id          条目唯一标识
 * @param userId      目标用户标识
 * @param typeId      通知类型标识（可选）
 * @param urgency     紧急程度
 * @param contentJson 通知内容 JSON 序列化
 * @param delivered   是否已投递
 * @param enqueuedAt  入队时间
 * @author zsg
 * @since 2026-03-13
 */
public record PassiveQueueEntry(
        String id,
        String userId,
        @Nullable String typeId,
        Urgency urgency,
        String contentJson,
        boolean delivered,
        Instant enqueuedAt
) {}
