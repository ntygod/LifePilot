package com.lifepilot.memory.semantic;

import com.lifepilot.memory.store.entity.TemporalEntity;

import java.util.Map;

/**
 * 合并结果 — 包含合并后的实体、冲突详情和是否为新版本。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record MergeResult(
        TemporalEntity mergedEntity,
        Map<String, ConflictDetail> conflicts,
        boolean isNewVersion
) {
    /** compact constructor：确保 conflicts 不可变。 */
    public MergeResult {
        conflicts = conflicts != null ? Map.copyOf(conflicts) : Map.of();
    }
}
