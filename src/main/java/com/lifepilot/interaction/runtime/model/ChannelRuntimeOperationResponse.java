package com.lifepilot.interaction.runtime.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 渠道连接器通用操作响应。
 *
 * @author zsg
 * @since 2026-04-02
 */
public record ChannelRuntimeOperationResponse(
        boolean success,
        String operationId,
        Map<String, Object> result,
        @Nullable String errorMessage
) {

    public ChannelRuntimeOperationResponse {
        result = result != null ? Map.copyOf(result) : Map.of();
    }
}
