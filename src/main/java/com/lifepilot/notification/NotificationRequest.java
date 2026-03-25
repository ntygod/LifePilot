package com.lifepilot.notification;

import com.lifepilot.interaction.model.ResponseContent;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 通知请求数据载体。
 *
 * <p>封装一次通知发送所需的全部信息，由 {@link NotificationService} 消费。
 *
 * @param targetUserId 目标用户标识
 * @param content      通知内容（{@link ResponseContent} 类型）
 * @param urgency      紧急程度
 * @param channel      指定渠道（可选，{@code null} 表示由通知服务按默认路由策略选择渠道）
 * @param typeId       通知类型标识（可选）
 * @param metadata     扩展元数据
 * @author zsg
 * @since 2026-03-13
 */
public record NotificationRequest(
        String targetUserId,
        ResponseContent content,
        Urgency urgency,
        @Nullable String channel,
        @Nullable String typeId,
        Map<String, String> metadata
) {

    /**
     * 紧凑构造器：对 metadata 做防御性拷贝。
     */
    public NotificationRequest {
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
}
