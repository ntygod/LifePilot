package com.lifepilot.memory.consolidation;

import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
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
 * <p>同步逻辑基于 {@code entity.name()} 与 {@code preferenceRule.key()} 的匹配：</p>
 * <ul>
 *   <li>L3 有 + L4 无 → 创建新偏好规则</li>
 *   <li>L3 有 + L4 有 → 强化已有偏好规则</li>
 *   <li>L3 归档 + L4 有 → 删除偏好规则</li>
 * </ul>
 *
 * <p>单条同步失败时捕获异常、记录 WARN 日志、继续处理剩余条目。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class PreferenceConsolidator {

    private static final Logger log = LoggerFactory.getLogger(PreferenceConsolidator.class);

    private static final String PREFERENCE_CATEGORY = "user-preference";

    private final SemanticMemory semanticMemory;
    private final ProceduralMemory proceduralMemory;

    /**
     * 构造偏好同步器。
     *
     * @param semanticMemory   L3 语义记忆服务
     * @param proceduralMemory L4 程序记忆服务
     */
    public PreferenceConsolidator(SemanticMemory semanticMemory,
                                   ProceduralMemory proceduralMemory) {
        this.semanticMemory = semanticMemory;
        this.proceduralMemory = proceduralMemory;
    }

    /**
     * 执行偏好同步，返回同步统计。
     *
     * <p>对比 L3 当前有效 PREFERENCE 实体与 L4 已有偏好规则，执行新建/强化/删除操作。
     * 单条同步失败时捕获异常、记录 WARN 日志、继续处理剩余条目。</p>
     *
     * @return 同步统计（新建数、强化数、删除数）
     */
    public PreferenceSyncStats consolidate() {
        // 1. 查询 L3 当前有效 PREFERENCE 实体
        var currentEntities = semanticMemory.findCurrentByType(EntityType.PREFERENCE);
        var currentEntityNames = currentEntities.stream()
                .collect(Collectors.toMap(TemporalEntity::name, e -> e, (a, b) -> a));

        // 2. 查询 L4 已有偏好规则
        var existingRules = proceduralMemory.getPreferences(PREFERENCE_CATEGORY);
        var rulesByKey = existingRules.stream()
                .collect(Collectors.toMap(PreferenceRule::key, r -> r, (a, b) -> a));

        int created = 0;
        int reinforced = 0;
        int deleted = 0;

        // 3. L3 有 + L4 无 → 创建；L3 有 + L4 有 → 强化
        for (var entity : currentEntities) {
            try {
                var existingRule = rulesByKey.get(entity.name());
                if (existingRule == null) {
                    // L3 有 + L4 无 → 创建新偏好规则
                    var now = Instant.now();
                    var newRule = new PreferenceRule(
                            UUID.randomUUID().toString(),
                            PREFERENCE_CATEGORY,
                            entity.name(),
                            entity.description() != null ? entity.description() : "",
                            0.5f,
                            "consolidation",
                            1,
                            now,
                            now
                    );
                    proceduralMemory.savePreference(newRule);
                    created++;
                    log.debug("偏好同步: 创建新规则, key={}", entity.name());
                } else {
                    // L3 有 + L4 有 → 强化
                    proceduralMemory.reinforcePreference(existingRule.ruleId());
                    reinforced++;
                    log.debug("偏好同步: 强化已有规则, key={}, ruleId={}", entity.name(), existingRule.ruleId());
                }
            } catch (Exception e) {
                log.warn("偏好同步: 处理实体失败, name={}, error={}", entity.name(), e.getMessage());
            }
        }

        // 4. L3 归档(无当前有效实体) + L4 有 → 删除
        for (var rule : existingRules) {
            try {
                if (!currentEntityNames.containsKey(rule.key())) {
                    proceduralMemory.deletePreference(rule.ruleId());
                    deleted++;
                    log.debug("偏好同步: 删除归档规则, key={}, ruleId={}", rule.key(), rule.ruleId());
                }
            } catch (Exception e) {
                log.warn("偏好同步: 删除规则失败, key={}, ruleId={}, error={}", rule.key(), rule.ruleId(), e.getMessage());
            }
        }

        log.info("偏好同步: 完成, created={}, reinforced={}, deleted={}", created, reinforced, deleted);
        return new PreferenceSyncStats(created, reinforced, deleted);
    }
}
