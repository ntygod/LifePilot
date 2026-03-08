package com.lifepilot.meta.infra.interaction;

import jakarta.annotation.Nullable;

/**
 * 交互响应 — 用户对交互请求的回复。
 *
 * @param interactionId 交互唯一标识
 * @param value         用户输入/选择的值（CONFIRM 类型为 null）
 * @param confirmed     CONFIRM 类型的 yes/no 结果
 * @param timedOut      是否超时
 * @author zsg
 * @since 2026-03-08
 */
public record InteractionResponse(
        String interactionId,
        @Nullable String value,
        boolean confirmed,
        boolean timedOut
) {

    /** 创建超时响应。 */
    public static InteractionResponse timeout(String interactionId) {
        return new InteractionResponse(interactionId, null, false, true);
    }
}
