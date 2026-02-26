package com.lifepilot.interaction.web.model;

import java.util.Map;

/**
 * A2UI 信号回传请求体。
 *
 * @param name      信号名称
 * @param payload   信号负载数据
 * @param sessionId 会话 ID
 * @author zsg
 * @since 2026-02-27
 */
public record SignalRequest(
        String name,
        Map<String, Object> payload,
        String sessionId
) {
    public SignalRequest {
        payload = payload != null ? Map.copyOf(payload) : Map.of();
    }
}
