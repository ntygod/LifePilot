package com.lifepilot.interaction.web.model;

import com.lifepilot.notification.NotificationSettingRecord;
import com.lifepilot.notification.Urgency;

import java.util.List;

/**
 * 通知设置 DTO，映射自 {@link NotificationSettingRecord}。
 *
 * @param typeId     通知类型标识
 * @param enabled    是否启用
 * @param channels   启用的渠道列表
 * @param minUrgency 最低紧急程度阈值
 * @author zsg
 * @since 2026-03-13
 */
public record NotificationSettingDto(
        String typeId,
        boolean enabled,
        List<String> channels,
        Urgency minUrgency
) {

    /** 紧凑构造器：防御性拷贝 channels。 */
    public NotificationSettingDto {
        channels = List.copyOf(channels);
    }

    /**
     * 从 {@link NotificationSettingRecord} 转换为 DTO。
     *
     * @param record 通知设置记录
     * @return 通知设置 DTO
     */
    public static NotificationSettingDto from(NotificationSettingRecord record) {
        return new NotificationSettingDto(
                record.typeId(),
                record.enabled(),
                record.channels(),
                record.minUrgency()
        );
    }
}
