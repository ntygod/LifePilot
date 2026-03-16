package com.lifepilot.meta.infra.browser;

import jakarta.annotation.Nullable;

import java.util.List;

/**
 * 无障碍树节点。
 *
 * @author zsg
 * @since 2026-03-16
 */
public record AccessibilityNode(
        String role,
        String name,
        @Nullable String value,
        boolean focusable,
        boolean interactive,
        List<AccessibilityNode> children
) {}
