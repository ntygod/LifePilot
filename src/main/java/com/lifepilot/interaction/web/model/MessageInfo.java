package com.lifepilot.interaction.web.model;

import java.time.Instant;

import org.springframework.lang.Nullable;

/**
 * 消息摘要信息。
 *
 * @param id        消息 ID
 * @param role      角色（user / assistant）
 * @param content   文本内容
 * @param a2ui      A2UI 组件树（可为 null）
 * @param timestamp 消息时间戳
 * @author zsg
 * @since 2026-02-27
 */
public record MessageInfo(
        String id,
        String role,
        String content,
        @Nullable A2uiComponentTree a2ui,
        Instant timestamp
) {}
