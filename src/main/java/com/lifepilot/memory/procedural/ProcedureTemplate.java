package com.lifepilot.memory.procedural;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 操作模板 — L4 程序记忆的核心数据载体。
 *
 * <p>从重复的成功执行轨迹中提炼而来，包含可复用的多步操作序列、
 * 变量占位符、成功率追踪和来源轨迹引用。</p>
 *
 * <p>V15 起加入 {@code sourceEntityId}（指向 L3 源 EXPERIENCE 实体）与
 * {@code deactivatedReason}（失活原因，非 null 表示已失活）。
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
        steps = steps != null ? List.copyOf(steps) : List.of();
        variables = variables != null ? Map.copyOf(variables) : Map.of();
        sourceTraceIds = sourceTraceIds != null ? List.copyOf(sourceTraceIds) : List.of();
    }

    /**
     * 兼容老调用点的 12 参构造器 — {@code sourceEntityId} 与 {@code deactivatedReason} 取默认
     * {@code null}（未关联源/活跃）。
     *
     * <p>新代码建议直接使用 14 参 canonical constructor，填入源实体 ID 以便 L3→L4 级联。</p>
     */
    public ProcedureTemplate(
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
            Instant updatedAt
    ) {
        this(templateId, name, description, triggerIntent, steps, variables,
                successRate, useCount, lastUsedAt, sourceTraceIds, createdAt, updatedAt,
                null, null);
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
