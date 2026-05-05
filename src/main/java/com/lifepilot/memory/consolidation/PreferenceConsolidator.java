package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.quality.MemoryQualityPolicy;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * L3→L4 偏好同步器 — 将 L3 语义记忆中的 PREFERENCE 实体同步为 L4 偏好规则。
 *
 * <p>同步逻辑：
 * <ul>
 *   <li>L3 有 + L4 无 → savePreference（新建）</li>
 *   <li>L3 有 + L4 有 → reinforcePreference（强化）</li>
 *   <li>L3 归档 + L4 有 → deletePreference（删除）</li>
 * </ul>
 * 单条同步失败时捕获异常、记录 WARN 日志、继续处理剩余条目。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class PreferenceConsolidator {

    private static final Logger log = LoggerFactory.getLogger(PreferenceConsolidator.class);

    private static final String PREFERENCE_CATEGORY = "user-preference";

    private final SemanticMemory semanticMemory;
    private final ProceduralMemory proceduralMemory;

    public PreferenceConsolidator(SemanticMemory semanticMemory,
                                  ProceduralMemory proceduralMemory) {
        this.semanticMemory = semanticMemory;
        this.proceduralMemory = proceduralMemory;
    }

    /**
     * 执行偏好同步，返回同步统计。
     */
    public PreferenceSyncStats consolidate() {
        // 1. 查询 L3 当前有效 PREFERENCE 实体
        var currentEntities = semanticMemory.findCurrentByType(EntityType.PREFERENCE).stream()
                .filter(MemoryQualityPolicy::canPromoteToProcedural)
                .toList();

        // 2. 查询 L4 已有偏好规则
        var existingRules = proceduralMemory.getPreferences(PREFERENCE_CATEGORY);

        // 按 key 索引 L4 规则
        var ruleByKey = existingRules.stream()
                .collect(Collectors.toMap(PreferenceRule::key, r -> r, (a, b) -> a));

        // 按 name 索引 L3 当前有效实体
        var currentEntityNames = currentEntities.stream()
                .map(TemporalEntity::name)
                .collect(Collectors.toSet());

        int created = 0;
        int reinforced = 0;
        int deleted = 0;

        // 3. L3 有 → 新建或强化
        for (var entity : currentEntities) {
            try {
                var existing = ruleByKey.get(entity.name());
                if (existing == null) {
                    // L3 有 + L4 无 → 新建 —— 填 sourceEntityId = L3 PREFERENCE 实体 id，
                    // 让后续 L3 CANCELLED/EXPIRED/SUPERSEDED 能通过 L4SyncListener
                    // 的 source_entity_id 反查命中并级联失活。
                    var rule = new PreferenceRule(
                            UUID.randomUUID().toString(),
                            PREFERENCE_CATEGORY,
                            entity.name(),
                            entity.description() != null ? entity.description() : "",
                            0.5f,
                            "consolidation",
                            1,
                            Instant.now(),
                            Instant.now(),
                            entity.id(),
                            null);
                    proceduralMemory.savePreference(rule);
                    created++;
                } else {
                    // L3 有 + L4 有 → 强化
                    proceduralMemory.reinforcePreference(existing.ruleId());
                    reinforced++;
                }
            } catch (Exception e) {
                log.warn("偏好同步: 单条处理失败, entityName={}, error={}", entity.name(), e.getMessage());
            }
        }

        // 4. L3 归档 + L4 有 → 删除
        for (var rule : existingRules) {
            if (!currentEntityNames.contains(rule.key())) {
                try {
                    proceduralMemory.deletePreference(rule.ruleId());
                    deleted++;
                } catch (Exception e) {
                    log.warn("偏好同步: 删除失败, ruleId={}, error={}", rule.ruleId(), e.getMessage());
                }
            }
        }

        log.info("偏好同步完成: created={}, reinforced={}, deleted={}", created, reinforced, deleted);
        return new PreferenceSyncStats(created, reinforced, deleted);
    }
}
