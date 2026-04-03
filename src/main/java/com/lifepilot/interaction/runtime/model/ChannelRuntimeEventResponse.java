package com.lifepilot.interaction.runtime.model;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 渠道运行时事件处理响应。
 *
 * @author zsg
 * @since 2026-03-29
 */
public record ChannelRuntimeEventResponse(
        boolean accepted,
        @Nullable String responseId,
        int statusCode,
        @Nullable String errorMessage,
        List<ChannelRuntimeDeliveryRequest> deliveries
) {

    public ChannelRuntimeEventResponse {
        deliveries = deliveries != null ? List.copyOf(deliveries) : List.of();
    }

    /** 重复事件的快捷响应，connector 收到后应忽略本次投递。 */
    public static ChannelRuntimeEventResponse duplicate(@Nullable String eventId) {
        return new ChannelRuntimeEventResponse(
                true, null, 200, "重复事件已忽略: " + eventId, List.of());
    }
}
