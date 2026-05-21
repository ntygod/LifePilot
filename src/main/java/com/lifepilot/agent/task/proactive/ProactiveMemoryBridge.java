package com.lifepilot.agent.task.proactive;

import com.lifepilot.memory.store.episodic.EpisodicMemory;
import com.lifepilot.memory.store.procedural.PreferenceRule;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.governance.lifecycle.events.ProactiveTaskCancelled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    /**
     * 主动任务 → L3 insight 关联查询/写入。允许为 null 以兼容未通过 JDBC
     * 装配的单测场景；生产 AutoConfiguration 始终注入。
     */
    @Nullable private final JdbcTemplate jdbcTemplate;
    /** Spring 事件总线 — Task 13 发 ProactiveTaskCancelled；单测可为空。 */
    @Nullable private ApplicationEventPublisher eventPublisher;

    public ProactiveMemoryBridge(@Nullable SemanticMemory semanticMemory,
                                  @Nullable EpisodicMemory episodicMemory,
                                  @Nullable ProceduralMemory proceduralMemory,
                                  GoalTrackingRepository goalTrackingRepository) {
        this(semanticMemory, episodicMemory, proceduralMemory, goalTrackingRepository, null);
    }

    public ProactiveMemoryBridge(@Nullable SemanticMemory semanticMemory,
                                  @Nullable EpisodicMemory episodicMemory,
                                  @Nullable ProceduralMemory proceduralMemory,
                                  GoalTrackingRepository goalTrackingRepository,
                                  @Nullable JdbcTemplate jdbcTemplate) {
        this.semanticMemory = semanticMemory;
        this.episodicMemory = episodicMemory;
        this.proceduralMemory = proceduralMemory;
        this.goalTrackingRepository = goalTrackingRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 注入 Spring {@link ApplicationEventPublisher} — setter 注入避免破坏
     * 现有多参构造器签名和大量手工装配测试。
     *
     * @param eventPublisher 事件发布器
     */
    public void setEventPublisher(@Nullable ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * memory-staleness spec：L3 实体进入 STALE_CANDIDATE / ARCHIVED 等非活跃态时被调用。
     *
     * <p>当前 Bridge 本身无缓存，只做日志记录作为未来扩展 hook：后续若为
     * getActiveGoals / getPreferences 等热路径加入缓存，可在此方法中清理对应条目。</p>
     *
     * @param entityId L3 实体 id
     */
    public void invalidateCacheForEntity(String entityId) {
        if (entityId == null || entityId.isBlank()) return;
        log.debug("主动引擎桥: 收到 L3 实体生命周期失效信号 entityId={} (hook 暂无缓存)", entityId);
    }

    // ── 目标查询（替代 IntentMemoryService） ──

    /** 获取用户活跃目标 — L3 GOAL 实体 + 引擎追踪状态合并。 */
    public List<GoalView> getActiveGoals() {
        if (semanticMemory == null) return List.of();
        try {
            // TODO(plan-1-后续): 接入 ProjectContext，主动任务按所属项目读取目标；
            // Plan 1 先按主账户维度读取，隔离项目的活跃目标暂不参与 proactive 追问。
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

    /**
     * 标记目标完成 — 归档 L3 实体 + 清理追踪 + 发
     * {@link com.lifepilot.memory.lifecycle.events.ProactiveTaskCancelled}。
     *
     * <p>事件 payload 中 {@code relatedInsightEntityIds} 来自
     * {@link #findInsightEntityIdsByTask(String)}，在归档前查询以避免
     * 外键级联清理掉关联行。事件订阅方（ProactiveTaskCancelListener，
     * Phase 1 挂载）负责将这些 insight 转 CANCELLED。</p>
     *
     * <p>taskId 即为 L3 GOAL 实体 id。</p>
     *
     * <p><b>事务语义</b>：整个方法在 {@code @Transactional} 事务内完成（归档 + 清理追踪 +
     * 事件发布注册），事件通过 {@link #publishAfterCommit(Object)} 走 AFTER_COMMIT，
     * 与 {@code SemanticMemory#publishAfterCommit} 同范式 —— 若主事务回滚，
     * {@link com.lifepilot.memory.lifecycle.listeners.ProactiveTaskCancelListener}
     * 不会被幻觉触发导致 insight 误置 CANCELLED。</p>
     *
     * @param entityId 主动任务 id（即 GOAL 实体 id）
     */
    @Transactional
    public void markGoalFulfilled(String entityId) {
        // 归档前先查关联 insight，避免外键级联清理掉关联行
        List<String> relatedInsightIds = findInsightEntityIdsByTask(entityId);
        if (semanticMemory != null) {
            try {
                semanticMemory.findById(entityId).ifPresent(semanticMemory::archive);
            } catch (Exception e) {
                log.debug("记忆桥接: 目标归档失败: {}", e.getMessage());
            }
        }
        goalTrackingRepository.deleteByEntityId(entityId);
        publishAfterCommit(new ProactiveTaskCancelled(entityId, relatedInsightIds));
        log.debug("记忆桥接: 目标归档完成, taskId={}, 级联 insight 数={}",
                entityId, relatedInsightIds.size());
    }

    /**
     * 事务提交后发布事件；无活跃事务时立即发布（fallback，保持单测/手工装配可用）。
     *
     * <p>与 {@link SemanticMemory} 的同名方法语义一致：
     * 回滚路径下不产生幻觉事件，避免下游 listener 基于幻觉事件更新派生存储。</p>
     *
     * @param event Spring ApplicationEvent
     */
    private void publishAfterCommit(Object event) {
        if (eventPublisher == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        eventPublisher.publishEvent(event);
                    } catch (Exception e) {
                        log.warn("记忆桥接: 事件发布失败, event={}, error={}", event, e.getMessage());
                    }
                }
            });
        } else {
            try {
                eventPublisher.publishEvent(event);
            } catch (Exception e) {
                log.warn("记忆桥接: 事件发布失败, event={}, error={}", event, e.getMessage());
            }
        }
    }

    /**
     * 查询指定主动任务（goal 实体 id 即为 taskId）关联的所有 L3 insight
     * 实体 id — 用于发布 {@code ProactiveTaskCancelled} 事件时的 payload。
     *
     * <p>关联表 {@code proactive_task_insight_links} 在 V17 迁移中建立，
     * 需要有明确 goal 归属的 insight 通过
     * {@link #linkInsightToTask(String, String)} 显式挂钩。
     * {@link ImplicitSignalCollector} 产出的全局隐式信号不走这里，因为
     * 它们与特定 goal 无关。</p>
     *
     * @param taskId 主动任务 id（即 GOAL 实体 id）
     * @return 关联的 insight 实体 id 列表；JdbcTemplate 未注入或表不存在时返回空
     */
    public List<String> findInsightEntityIdsByTask(String taskId) {
        if (jdbcTemplate == null) {
            return List.of();
        }
        try {
            return jdbcTemplate.queryForList(
                    "SELECT entity_id FROM proactive_task_insight_links WHERE task_id = ?",
                    String.class, taskId);
        } catch (Exception e) {
            log.debug("记忆桥接: insight 关联查询失败, taskId={}, error={}", taskId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 将 L3 insight 实体挂钩到主动任务 — 显式建立
     * {@code (task_id, entity_id)} 关联，供 {@code markGoalFulfilled} 级联取消。
     *
     * <p>幂等：{@code PRIMARY KEY (task_id, entity_id)} 保证重复调用只留一条。</p>
     *
     * @param taskId   主动任务 id（即 GOAL 实体 id）
     * @param entityId L3 insight 实体 id
     */
    public void linkInsightToTask(String taskId, String entityId) {
        if (jdbcTemplate == null) {
            return;
        }
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO proactive_task_insight_links(task_id, entity_id, created_at)
                    VALUES(?, ?, ?)
                    ON CONFLICT(task_id, entity_id) DO NOTHING
                    """,
                    taskId, entityId, Instant.now().toString());
        } catch (Exception e) {
            log.debug("记忆桥接: insight 关联写入失败, taskId={}, entityId={}, error={}",
                    taskId, entityId, e.getMessage());
        }
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

    /** 巩固画像实体名 — 与 UserProfileConsolidator 约定。 */
    private static final String CONSOLIDATED_PROFILE_NAME = "__consolidated_profile";

    /**
     * 获取用户画像 — 优先读巩固后的连贯画像，降级拼接零散实体。
     *
     * <p>巩固画像由 UserProfileConsolidator 在定时巩固管线中 LLM 生成，
     * 是一段 200 字的第三人称自然语言描述。如果尚未生成过，则降级为
     * 从 L3 零散实体拼接列表形式。</p>
     */
    public String getUserPortrait() {
        if (semanticMemory == null) return "";
        try {
            // TODO(plan-1-后续): 接入 ProjectContext，按当前项目读取画像；
            // Plan 1 先按主账户维度读取巩固画像和零散实体。
            // 优先读巩固后的画像
            var consolidated = semanticMemory.findCurrentByNameAndType(
                    CONSOLIDATED_PROFILE_NAME, EntityType.CUSTOM, MemoryReadFilter.userProfile());
            if (consolidated.isPresent()) {
                var desc = consolidated.get().description();
                if (desc != null && !desc.isBlank()) return desc;
            }

            // 降级：拼接零散实体
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
            // TODO(plan-1-后续): 接入 ProjectContext，按当前项目读取经验；
            // Plan 1 先按主账户维度读取所有 agent 经验。
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
                // 强化已有规则 — 保留原有 sourceEntityId / deactivatedReason，避免被主动写入覆盖。
                proceduralMemory.savePreference(new PreferenceRule(
                        rule.ruleId(), category, key,
                        String.format("%.3f", newValue),
                        Math.min(1f, rule.confidence() + 0.05f),
                        "proactive-engine",
                        rule.observationCount() + 1,
                        rule.createdAt(), now,
                        rule.sourceEntityId(), rule.deactivatedReason()));
            } else {
                // 主动引擎自生偏好无 L3 源实体，sourceEntityId 保持 null；
                // L4SyncListener 的 WHERE source_entity_id = ? 不会命中这些记录，no-op 即可。
                proceduralMemory.savePreference(new PreferenceRule(
                        UUID.randomUUID().toString(), category, key,
                        String.format("%.3f", signal),
                        0.3f, "proactive-engine", 1,
                        now, now, null, null));
            }
        } catch (Exception e) {
            log.debug("记忆桥接: 偏好写入失败: category={}, key={}, error={}", category, key, e.getMessage());
        }
    }

    /**
     * 将主动引擎洞察回写到 L3 语义记忆，使其可被 HybridRetriever 检索。
     *
     * @param category 偏好类别（如 "proactive-domain"）
     * @param key      偏好键（如行为名）
     * @param value    信号值 [0, 1]
     * @param evidence 证据描述
     */
    public void syncInsightToL3(String category, String key, float value, String evidence) {
        if (semanticMemory == null) return;
        try {
            String entityName = "proactive_insight_" + category + "_" + key;
            String description = "主动引擎洞察: %s/%s, 信号=%.2f, 证据=%s".formatted(category, key, value, evidence);

            var entity = new TemporalEntity(
                    null, EntityType.PREFERENCE, entityName, description,
                    Map.of("category", category, "key", key, "signal", value, "evidence", evidence),
                    1, true, Instant.now(), null, null,
                    0.8f, Math.max(0.5f, value), 0, null, Instant.now(), Instant.now())
                    .withQuality(MemoryEvidenceKind.BEHAVIOR_INFERRED, MemoryTrustLevel.INFERRED,
                            Math.max(0.50f, Math.min(0.75f, value)), 1, null);
            semanticMemory.upsertWithConflictDetection(entity, "proactive-engine");
            log.debug("记忆桥接: 主动洞察回写 L3, name={}", entityName);
        } catch (Exception e) {
            log.debug("记忆桥接: 主动洞察回写 L3 失败: {}", e.getMessage());
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
