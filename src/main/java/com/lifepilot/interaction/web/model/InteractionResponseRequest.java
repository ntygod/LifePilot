package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

/**
 * 用户交互回传请求体。
 *
 * @param value     用户输入或选择结果
 * @param confirmed 是否确认本次交互
 * @param timedOut  是否视为超时/取消
 * @author zsg
 * @since 2026-03-25
 */
public record InteractionResponseRequest(
        @Nullable String value,
        boolean confirmed,
        boolean timedOut
) {}
