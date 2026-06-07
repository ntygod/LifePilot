package com.lifepilot.agent.initiative.pool;

import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 想法池 — 管理所有想法的生命周期。
 *
 * <p>核心职责：意图级去重、生命周期管理、优先级排序。
 * 同一 intentKey 在活跃状态下只能有一个想法。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class ThoughtPool {

    private static final Logger log = LoggerFactory.getLogger(ThoughtPool.class);

    private final int maxActiveThoughts;
    private final Duration brewingTtl;
    private final Duration readyTtl;
    /** 想法持久化仓库（initiative_thoughts）—— null 时退化为纯内存（测试/未启用持久化）。 */
    @Nullable
    private final ThoughtRepository repository;
    private final ConcurrentHashMap<String, Thought> thoughts = new ConcurrentHashMap<>();

    public ThoughtPool(int maxActiveThoughts, Duration brewingTtl, Duration readyTtl) {
        this(maxActiveThoughts, brewingTtl, readyTtl, null);
    }

    public ThoughtPool(int maxActiveThoughts, Duration brewingTtl, Duration readyTtl,
                       @Nullable ThoughtRepository repository) {
        this.maxActiveThoughts = maxActiveThoughts;
        this.brewingTtl = brewingTtl;
        this.readyTtl = readyTtl;
        this.repository = repository;
        loadActiveFromRepository();
    }

    /** 启动时从库恢复活跃想法（BREWING/READY），避免重启丢失去重/冷却状态。 */
    private void loadActiveFromRepository() {
        if (repository == null) return;
        try {
            for (var t : repository.findByState(ThoughtState.BREWING)) {
                thoughts.put(t.id(), t);
            }
            for (var t : repository.findByState(ThoughtState.READY)) {
                thoughts.put(t.id(), t);
            }
            if (!thoughts.isEmpty()) {
                log.info("想法池: 从库恢复活跃想法 {} 个", thoughts.size());
            }
        } catch (Exception e) {
            log.warn("想法池: 从库恢复失败，以空池启动: {}", e.getMessage());
        }
    }

    /** 持久化想法（best-effort，失败仅 warn 不阻塞思考）。 */
    private void persist(Thought thought) {
        if (repository == null) return;
        try {
            repository.save(thought);
        } catch (Exception e) {
            log.warn("想法池: 持久化失败, id={}, error={}", thought.id(), e.getMessage());
        }
    }

    /**
     * 提交新想法。如果同 intentKey 已有活跃想法，则合并证据并提升成熟度。
     *
     * @return 实际存入的想法（可能是合并后的）
     */
    public Thought submit(Thought thought) {
        // 意图级去重
        var existing = findActiveByIntentKey(thought.intentKey());
        if (existing != null) {
            // 合并：提升成熟度
            float newMaturity = Math.min(1.0f, existing.maturity() + 0.1f);
            var merged = existing.withMaturity(newMaturity);
            thoughts.put(merged.id(), merged);
            persist(merged);
            log.debug("想法池: 合并到已有想法, intentKey={}, maturity={}", thought.intentKey(), newMaturity);
            return merged;
        }

        // 容量检查
        long activeCount = thoughts.values().stream()
                .filter(t -> t.state().isActive())
                .count();
        if (activeCount >= maxActiveThoughts) {
            log.debug("想法池: 容量已满({}), 丢弃新想法: {}", maxActiveThoughts, thought.intentKey());
            return thought.withState(ThoughtState.DISMISSED);
        }

        thoughts.put(thought.id(), thought);
        persist(thought);
        log.debug("想法池: 新想法入池, id={}, intentKey={}, maturity={}",
                thought.id(), thought.intentKey(), thought.maturity());
        return thought;
    }

    /**
     * 获取所有就绪的想法（按成熟度降序）。
     */
    public List<Thought> getReadyThoughts() {
        return thoughts.values().stream()
                .filter(Thought::isReady)
                .sorted(Comparator.comparingDouble(Thought::maturity).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 更新想法状态。
     */
    public void transition(String thoughtId, ThoughtState newState) {
        thoughts.computeIfPresent(thoughtId, (id, thought) -> thought.withState(newState));
        if (repository != null) {
            try {
                repository.updateState(thoughtId, newState);
            } catch (Exception e) {
                log.warn("想法池: 状态持久化失败, id={}, error={}", thoughtId, e.getMessage());
            }
        }
    }

    /**
     * 清理过期想法。
     *
     * @return 清理数量
     */
    public int cleanup() {
        int cleaned = 0;
        for (var entry : thoughts.entrySet()) {
            var thought = entry.getValue();
            if (thought.state().isTerminal()) {
                // 终态想法保留一段时间后清理（用于冷却期判断）
                continue;
            }
            if (thought.isExpired(brewingTtl, readyTtl)) {
                thoughts.put(entry.getKey(), thought.withState(ThoughtState.DISMISSED));
                if (repository != null) {
                    try {
                        repository.updateState(entry.getKey(), ThoughtState.DISMISSED);
                    } catch (Exception e) {
                        log.warn("想法池: 清理状态持久化失败, id={}, error={}", entry.getKey(), e.getMessage());
                    }
                }
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.debug("想法池: 清理过期想法 {}", cleaned);
        }
        return cleaned;
    }

    /**
     * 查找指定 intentKey 的活跃想法。
     */
    private Thought findActiveByIntentKey(String intentKey) {
        return thoughts.values().stream()
                .filter(t -> t.state().isActive() && t.intentKey().equals(intentKey))
                .findFirst()
                .orElse(null);
    }

    public int activeCount() {
        return (int) thoughts.values().stream().filter(t -> t.state().isActive()).count();
    }

    /** 活跃想法快照（按成熟度降序）—— 供观测/诊断使用。 */
    public List<Thought> activeThoughts() {
        return thoughts.values().stream()
                .filter(t -> t.state().isActive())
                .sorted(Comparator.comparingDouble(Thought::maturity).reversed())
                .collect(Collectors.toList());
    }

    public Optional<Thought> findById(String id) {
        return Optional.ofNullable(thoughts.get(id));
    }
}
