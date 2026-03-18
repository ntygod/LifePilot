package com.lifepilot.memory.consolidation;

/**
 * 偏好同步统计 — 记录 L3→L4 偏好同步的操作计数。
 *
 * @author zsg
 * @since 2026-03-18
 */
public record PreferenceSyncStats(int created, int reinforced, int deleted) {}
