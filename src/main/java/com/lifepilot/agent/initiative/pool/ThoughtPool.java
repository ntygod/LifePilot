package com.lifepilot.agent.initiative.pool;

import com.lifepilot.agent.initiative.maturity.MaturityModel;
import com.lifepilot.agent.initiative.model.Evidence;
import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.initiative.model.ThoughtState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    /** 想法持久化仓库（initiative_thoughts）。 */
    private final ThoughtRepository repository;
    /** 成熟度演化模型（thought-maturity-evolution）—— 强化、截止升温、停滞衰减的确定性计算。 */
    private final MaturityModel maturityModel;
    private final ConcurrentHashMap<String, Thought> thoughts = new ConcurrentHashMap<>();

    public ThoughtPool(int maxActiveThoughts, Duration brewingTtl, Duration readyTtl,
                       ThoughtRepository repository,
                       MaturityModel maturityModel) {
        this.maxActiveThoughts = maxActiveThoughts;
        this.brewingTtl = brewingTtl;
        this.readyTtl = readyTtl;
        this.repository = Objects.requireNonNull(repository, "想法持久化仓库不能为空");
        this.maturityModel = Objects.requireNonNull(maturityModel, "成熟度模型不能为空");
        loadActiveFromRepository();
    }

    /** 启动时从库恢复活跃想法（BREWING/READY），避免重启丢失去重/冷却状态。 */
    private void loadActiveFromRepository() {
        for (var t : repository.findByState(ThoughtState.BREWING)) {
            thoughts.put(t.id(), t);
        }
        for (var t : repository.findByState(ThoughtState.READY)) {
            thoughts.put(t.id(), t);
        }
        if (!thoughts.isEmpty()) {
            log.info("想法池: 从库恢复活跃想法 {} 个", thoughts.size());
        }
    }

    /** 持久化想法。 */
    private void persist(Thought thought) {
        repository.save(thought);
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
            persist(merged);
            thoughts.put(merged.id(), merged);
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

        persist(thought);
        thoughts.put(thought.id(), thought);
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
        Thought existing = thoughts.get(thoughtId);
        if (existing == null) {
            return;
        }
        Thought updated = existing.withState(newState);
        repository.updateState(thoughtId, newState);
        thoughts.put(thoughtId, updated);
    }

    /**
     * 标记想法已表达，并记录主动对话 session。
     */
    public Thought markExpressed(String thoughtId, String conversationId) {
        Thought existing = thoughts.get(thoughtId);
        if (existing == null) {
            throw new IllegalArgumentException("想法不存在: " + thoughtId);
        }
        Thought expressed = existing.withExpression(conversationId);
        repository.markExpressed(thoughtId, conversationId);
        thoughts.put(thoughtId, expressed);
        return expressed;
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
                repository.updateState(entry.getKey(), ThoughtState.DISMISSED);
                thoughts.put(entry.getKey(), thought.withState(ThoughtState.DISMISSED));
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
     * <p>截止临近升温、停滞衰减；跌破淘汰下限的想法迁移为 DISMISSED。单想法计算异常被隔离，
     * 不影响其余。</p>
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
                persistEvolution(evolved, thought.state() != result.state());
                thoughts.put(entry.getKey(), evolved);
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
        repository.save(evolved);
        if (stateChanged) {
            repository.updateState(evolved.id(), evolved.state());
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
