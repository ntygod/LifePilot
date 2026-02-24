package com.lifepilot.memory.semantic;

/**
 * 冲突详情 — 记录单个属性的冲突信息和解决方式。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record ConflictDetail(
        String fieldName,
        Object oldValue,
        Object newValue,
        ConflictResolution resolution
) {}
