package com.lifepilot.interaction.web.model;

import java.util.Map;

/**
 * A2UI 信号，用户与组件交互时产生。
 *
 * @param name    信号名称（如 "todo.complete"）
 * @param payload 信号负载数据
 * @author zsg
 * @since 2026-02-27
 */
public record A2uiSignal(
        String name,
        Map<String, Object> payload
) {
    public A2uiSignal {
        payload = payload != null ? Map.copyOf(payload) : Map.of();
    }
}
