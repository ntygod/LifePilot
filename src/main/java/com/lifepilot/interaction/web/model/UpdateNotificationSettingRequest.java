package com.lifepilot.interaction.web.model;

import com.lifepilot.notification.Urgency;

import java.util.List;

/**
 * 更新通知设置请求体。
 *
 * @param userId     用户标识
 * @param enabled    是否启用
 * @param channels   启用的渠道列表
 * @param minUrgency 最低紧急程度阈值
 * @author zsg
 * @since 2026-03-13
 */
public record UpdateNotificationSettingRequest(
        String userId,
        boolean enabled,
        List<String> channels,
        Urgency minUrgency
) {

    /** 紧凑构造器：防御性拷贝 channels。 */
    public UpdateNotificationSettingRequest {
        channels = channels != null ? List.copyOf(channels) : List.of();
    }
}
