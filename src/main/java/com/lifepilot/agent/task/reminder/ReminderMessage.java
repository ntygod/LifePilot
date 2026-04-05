package com.lifepilot.agent.task.reminder;

import org.springframework.lang.Nullable;

import java.util.Objects;

/**
 * 提醒文案生成结果。
 *
 * @param body       提醒正文
 * @param mode       文案生成模式
 * @param providerId 生成服务提供方
 * @param modelName  生成模型名称
 * @author zsg
 * @since 2026-03-28
 */
public record ReminderMessage(
        String body,
        String mode,
        @Nullable String providerId,
        @Nullable String modelName
) {

    public ReminderMessage {
        body = Objects.requireNonNullElse(body, "").trim();
        mode = Objects.requireNonNullElse(mode, "fallback").trim();
    }
}
