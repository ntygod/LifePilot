package com.lifepilot.agent.initiative.pool;

import com.lifepilot.agent.initiative.maturity.MaturityModel;
import com.lifepilot.agent.initiative.model.Evidence;
import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
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
    /** 成熟度演化模型（thought-maturity-evolution）—— 强化、截止升温、停滞衰减的确定性计算。 */
    private final MaturityModel maturityModel;
    private final ConcurrentHashMap<String, Thought> thoughts = new ConcurrentHashMap<>();

    public ThoughtPool(int maxActiveThoughts, Duration brewingTtl, Duration readyTtl,
                       @Nullable ThoughtRepository repository,
                       MaturityModel maturityModel) {
        this.maxActiveThoughts = maxActiveThoughts;
        this.brewingTtl = brewingTtl;
        this.readyTtl = readyTtl;
        this.repository = repository;
        this.maturityModel = maturityModel;
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
            // 证据强化：按新证据权重提升成熟度（边际递减），合并证据，刷新强化时间
            float evidenceWeight = maxRelevance(thought.evidence());
            float newMaturity = maturityModel.reinforce(existing.maturity(), evidenceWeight);
            var mergedEvidence = mergeEvidence(existing.evidence(), thought.evidence());
            float newConfidence = Math.max(existing.confidence(), thought.confidence());
            var newState = maturityModel.resolveState(newMaturity, existing.state());
            var merged = existing.reinforcedWith(
                    mergedEvidence, newMaturity, newConfidence, newState, Instant.now());
            thoughts.put(merged.id(), merged);
            persist(merged);
            log.debug("想法池: 强化已有想法, intentKey={}, maturity={}->{}, weight={}",
                    thought.intentKey(), existing.maturity(), newMaturity, evidenceWeight);
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
     * 成熟度演化 —— 对活跃想法按当前时间重算 maturity 并应用状态迁移（thought-maturity-evolution）。
     *
     * <p>截止临近升温、停滞衰减；跌破淘汰下限的想法迁移为 DISMISSED。变更的想法持久化
     * （best-effort，失败仅 warn 不阻塞）。单想法计算异常被隔离，不影响其余。</p>
     *
     * @param now 当前时间（由调用方传入，保证可复现）
     * @return 发生变更的想法数
     */
    public int evolve(Instant now) {
        int changed = 0;
        for (var entry : thoughts.entrySet()) {
            var thought = entry.getValue();
            if (!thought.state().isActive()) continue;  // 仅演化活跃想法（BREWING/READY）
            try {
                var result = maturityModel.evolve(thought, now);
                if (!result.changed()) continue;
                var evolved = thought.withEvolution(result.maturity(), result.state());
                thoughts.put(entry.getKey(), evolved);
                persistEvolution(evolved, thought.state() != result.state());
                changed++;
            } catch (Exception e) {
                log.warn("想法池: 演化计算失败，跳过, id={}, error={}", entry.getKey(), e.getMessage());
            }
        }
        if (changed > 0) {
            log.debug("想法池: 成熟度演化更新 {} 个想法", changed);
        }
        return changed;
    }

    /** 持久化演化结果（成熟度/状态/强化时间）；状态变更同时落 updateState。 */
    private void persistEvolution(Thought evolved, boolean stateChanged) {
        if (repository == null) return;
        try {
            repository.save(evolved);
            if (stateChanged) {
                repository.updateState(evolved.id(), evolved.state());
            }
        } catch (Exception e) {
            log.warn("想法池: 演化持久化失败, id={}, error={}", evolved.id(), e.getMessage());
        }
    }

    /** 取证据列表中最大 relevance 作为强化权重；空列表回退 0.5。 */
    private static float maxRelevance(List<Evidence> evidence) {
        if (evidence == null || evidence.isEmpty()) return 0.5f;
        float max = 0f;
        for (var e : evidence) {
            if (e.relevance() > max) max = e.relevance();
        }
        return max;
    }

    /** 合并证据列表（去重按 sourceType+sourceId）。 */
    private static List<Evidence> mergeEvidence(List<Evidence> existing, List<Evidence> incoming) {
        var merged = new LinkedHashMap<String, Evidence>();
        for (var e : existing) merged.put(e.sourceType() + ":" + e.sourceId(), e);
        for (var e : incoming) merged.putIfAbsent(e.sourceType() + ":" + e.sourceId(), e);
        return new ArrayList<>(merged.values());
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
