package com.lifepilot.agent.task.proactive;

import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 主动引擎记忆桥接 — 从 L2/L3/L4 记忆模块消费数据的唯一入口。
 *
 * <p>所有记忆 bean 均可为 null（记忆模块可能未启用），
 * null 时返回空列表或默认值，不抛异常。</p>
 *
 * @author zsg
 * @since 2026-04-15
 */
public class ProactiveMemoryBridge {

    private static final Logger log = LoggerFactory.getLogger(ProactiveMemoryBridge.class);

    /** 偏好加权平均的衰减因子（新观察权重 30%，旧值权重 70%）。 */
    private static final float PREFERENCE_ALPHA = 0.3f;

    @Nullable private final SemanticMemory semanticMemory;
    @Nullable private final EpisodicMemory episodicMemory;
    @Nullable private final ProceduralMemory proceduralMemory;
    private final GoalTrackingRepository goalTrackingRepository;

    public ProactiveMemoryBridge(@Nullable SemanticMemory semanticMemory,
                                  @Nullable EpisodicMemory episodicMemory,
                                  @Nullable ProceduralMemory proceduralMemory,
                                  GoalTrackingRepository goalTrackingRepository) {
        this.semanticMemory = semanticMemory;
        this.episodicMemory = episodicMemory;
        this.proceduralMemory = proceduralMemory;
        this.goalTrackingRepository = goalTrackingRepository;
    }

    // ── 目标查询（替代 IntentMemoryService） ──

    /** 获取用户活跃目标 — L3 GOAL 实体 + 引擎追踪状态合并。 */
    public List<GoalView> getActiveGoals() {
        if (semanticMemory == null) return List.of();
        try {
            var goals = semanticMemory.findCurrentByType(EntityType.GOAL, MemoryReadFilter.userProfile());
            return goals.stream()
                    .map(this::toGoalView)
                    .toList();
        } catch (Exception e) {
            log.debug("记忆桥接: 目标查询失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 递增目标追问次数 + L3 访问计数。 */
    public void incrementCheckCount(String entityId) {
        goalTrackingRepository.incrementCheckCount(entityId);
        if (semanticMemory != null) {
            try {
                semanticMemory.incrementAccessCount(entityId);
            } catch (Exception e) {
                log.debug("记忆桥接: L3 访问计数更新跳过: {}", e.getMessage());
            }
        }
    }

    /** 标记目标完成 — 归档 L3 实体 + 清理追踪。 */
    public void markGoalFulfilled(String entityId) {
        if (semanticMemory != null) {
            try {
                semanticMemory.findById(entityId).ifPresent(semanticMemory::archive);
            } catch (Exception e) {
                log.debug("记忆桥接: 目标归档失败: {}", e.getMessage());
            }
        }
        goalTrackingRepository.deleteByEntityId(entityId);
    }

    /**
     * 为目标组装丰富上下文 — 供 reason() 的 LLM prompt 使用。
     *
     * <p>包含：实体演变历史 + 关联实体 + 最近相关对话片段。</p>
     */
    public String enrichGoalContext(String entityId, String goalName) {
        var sb = new StringBuilder();

        // 实体演变历史
        if (semanticMemory != null) {
            try {
                var history = semanticMemory.getChangeHistory(goalName, EntityType.GOAL);
                if (history.size() > 1) {
                    sb.append("目标演变：\n");
                    for (var version : history) {
                        sb.append("- ").append(version.validFrom()).append(" ");
                        sb.append(version.description() != null ? version.description() : version.name());
                        sb.append(" (重要度:").append(String.format("%.1f", version.importanceScore())).append(")\n");
                    }
                }
            } catch (Exception e) {
                log.debug("记忆桥接: 历史查询跳过: {}", e.getMessage());
            }

            // 关联实体
            try {
                var related = semanticMemory.findRelated(entityId, 2);
                if (!related.isEmpty()) {
                    sb.append("相关：");
                    sb.append(related.stream()
                            .limit(5)
                            .map(e -> "[" + e.type().label() + "] " + e.name())
                            .collect(Collectors.joining("、")));
                    sb.append("\n");
                }
            } catch (Exception e) {
                log.debug("记忆桥接: 关联查询跳过: {}", e.getMessage());
            }
        }

        // 最近相关对话
        if (episodicMemory != null) {
            try {
                var conversations = episodicMemory.search(goalName);
                if (!conversations.isEmpty()) {
                    sb.append("相关对话：\n");
                    conversations.stream().limit(3).forEach(conv -> {
                        String summary = conv.summary() != null ? conv.summary() : conv.goal();
                        if (summary != null && !summary.isBlank()) {
                            sb.append("- ").append(summary).append("\n");
                        }
                    });
                }
            } catch (Exception e) {
                log.debug("记忆桥接: 对话检索跳过: {}", e.getMessage());
            }
        }

        return sb.toString();
    }

    // ── 用户画像（替代 UserProfileService） ──

    /** 组装用户画像 — 从 L3 USER_PROFILE 范围实体拼接自然语言。 */
    public String getUserPortrait() {
        if (semanticMemory == null) return "";
        try {
            var filter = MemoryReadFilter.userProfile();
            var sb = new StringBuilder();
            for (var type : List.of(EntityType.PREFERENCE, EntityType.HABIT, EntityType.GOAL, EntityType.SKILL)) {
                var entities = semanticMemory.findCurrentByType(type, filter);
                for (var entity : entities) {
                    sb.append("- [").append(type.label()).append("] ").append(entity.name());
                    if (entity.description() != null && !entity.description().isBlank()) {
                        sb.append(": ").append(entity.description());
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("记忆桥接: 画像组装失败: {}", e.getMessage());
            return "";
        }
    }

    // ── 经验（替代 ReflectionService） ──

    /** 获取最近 Agent 经验 — 从 L3 EXPERIENCE 实体。 */
    public String getRecentExperiences() {
        if (semanticMemory == null) return "";
        try {
            var experiences = semanticMemory.findCurrentByType(
                    EntityType.EXPERIENCE, MemoryReadFilter.agentExperience());
            if (experiences.isEmpty()) return "";
            return experiences.stream()
                    .limit(5)
                    .map(e -> "- " + e.name() + (e.description() != null ? ": " + e.description() : ""))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.debug("记忆桥接: 经验查询失败: {}", e.getMessage());
            return "";
        }
    }

    // ── 偏好查询/写入（替代 PreferenceRepository + PreferenceLearner） ──

    /** 查询偏好规则。 */
    public List<PreferenceRule> getPreferences(String category) {
        if (proceduralMemory == null) return List.of();
        try {
            return proceduralMemory.getPreferences(category);
        } catch (Exception e) {
            log.debug("记忆桥接: 偏好查询失败: category={}, error={}", category, e.getMessage());
            return List.of();
        }
    }

    /**
     * 观察偏好信号 — 加权平均写入 L4。
     *
     * <p>如果已有同 category+key 的规则，用 EWMA 更新；否则新建。</p>
     */
    public synchronized void observePreference(String category, String key, float signal) {
        if (proceduralMemory == null) return;
        try {
            var existing = proceduralMemory.findPreference(category, key);
            Instant now = Instant.now();
            if (existing.isPresent()) {
                var rule = existing.get();
                float oldValue = parseFloat(rule.value(), 0.5f);
                float newValue = oldValue * (1 - PREFERENCE_ALPHA) + signal * PREFERENCE_ALPHA;
                proceduralMemory.savePreference(new PreferenceRule(
                        rule.ruleId(), category, key,
                        String.format("%.3f", newValue),
                        Math.min(1f, rule.confidence() + 0.05f),
                        "proactive-engine",
                        rule.observationCount() + 1,
                        rule.createdAt(), now));
            } else {
                proceduralMemory.savePreference(new PreferenceRule(
                        UUID.randomUUID().toString(), category, key,
                        String.format("%.3f", signal),
                        0.3f, "proactive-engine", 1,
                        now, now));
            }
        } catch (Exception e) {
            log.debug("记忆桥接: 偏好写入失败: category={}, key={}, error={}", category, key, e.getMessage());
        }
    }

    /** 解析偏好值为 float，解析失败返回默认值。 */
    public static float parseFloat(String value, float defaultValue) {
        try {
            return Float.parseFloat(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    // ── 内部方法 ──

    private GoalView toGoalView(TemporalEntity entity) {
        var tracking = goalTrackingRepository.findByEntityId(entity.id());
        return new GoalView(
                entity.id(),
                entity.name(),
                entity.description(),
                entity.importanceScore(),
                entity.accessCount(),
                entity.validFrom(),
                tracking != null ? tracking.checkCount() : 0,
                tracking != null ? tracking.lastFollowUpAt() : null,
                entity.properties());
    }
}
