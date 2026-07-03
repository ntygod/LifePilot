package com.lifepilot.memory.store.procedural;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 操作模板 — L4 程序记忆的核心数据载体。
 *
 * <p>从重复的成功执行轨迹中提炼而来，包含可复用的多步操作序列、
 * 变量占位符、成功率追踪和来源轨迹引用。</p>
 *
 * <p>{@code sourceEntityId} 指向 L3 源 EXPERIENCE 实体，{@code deactivatedReason}
 * 为失活原因，非 null 表示已失活。
 * {@code L4SyncListener} 通过 {@code source_entity_id} 反查模板并置
 * {@code deactivated_reason}，实现 L3 失活 → L4 级联失活。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public record ProcedureTemplate(
        String templateId,
        String name,
        String description,
        String triggerIntent,
        List<TemplateStep> steps,
        Map<String, String> variables,
        float successRate,
        int useCount,
        @Nullable Instant lastUsedAt,
        List<String> sourceTraceIds,
        Instant createdAt,
        Instant updatedAt,
        @Nullable String sourceEntityId,
        @Nullable String deactivatedReason
) {
    /** compact constructor：确保集合字段不可变。 */
    public ProcedureTemplate {
        Objects.requireNonNull(templateId, "模板 ID 不能为空");
        Objects.requireNonNull(name, "模板名称不能为空");
        Objects.requireNonNull(description, "模板描述不能为空");
        Objects.requireNonNull(triggerIntent, "触发意图不能为空");
        steps = List.copyOf(Objects.requireNonNull(steps, "模板步骤不能为空"));
        variables = Map.copyOf(Objects.requireNonNull(variables, "模板变量不能为空"));
        sourceTraceIds = List.copyOf(Objects.requireNonNull(sourceTraceIds, "来源轨迹 ID 列表不能为空"));
        Objects.requireNonNull(createdAt, "模板创建时间不能为空");
        Objects.requireNonNull(updatedAt, "模板更新时间不能为空");
        if (!(successRate >= 0.0f && successRate <= 1.0f)) {
            throw new IllegalArgumentException("模板成功率必须在 [0,1] 范围内: " + successRate);
        }
        if (useCount < 0) {
            throw new IllegalArgumentException("模板使用次数不能为负数: " + useCount);
        }
    }

    /**
     * 判断模板是否可靠 — 成功率和使用次数均达到阈值。
     *
     * @param minReliability 最低成功率阈值
     * @param minUseCount    最低使用次数阈值
     * @return 当 successRate >= minReliability 且 useCount >= minUseCount 时返回 true
     */
    public boolean isReliable(float minReliability, int minUseCount) {
        return successRate >= minReliability && useCount >= minUseCount;
    }

    /**
     * 判断模板是否过时 — 超过指定天数未被使用。
     *
     * @param staleDays 过时天数阈值
     * @return 当 lastUsedAt 非空且距今超过 staleDays 天时返回 true
     */
    public boolean isStale(int staleDays) {
        return lastUsedAt != null && lastUsedAt.isBefore(Instant.now().minus(Duration.ofDays(staleDays)));
    }
}
