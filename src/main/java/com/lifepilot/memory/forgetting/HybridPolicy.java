package com.lifepilot.memory.forgetting;

import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Hybrid 遗忘策略 — 四阶段顺序执行 FIFO → LRU → PriorityDecay → ReflectionSummary。
 *
 * <p>每阶段独立预算限制（总预算 / 4），防止单阶段遗忘过多实体。
 * 每阶段操作前一阶段的剩余候选（已选中的实体被排除），
 * 最终合并所有阶段结果，总数不超过预算。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class HybridPolicy implements ForgettingPolicy {

    private static final Logger log = LoggerFactory.getLogger(HybridPolicy.class);

    private final FifoPolicy fifoPolicy;
    private final LruPolicy lruPolicy;
    private final PriorityDecayPolicy priorityDecayPolicy;
    private final ReflectionSummaryPolicy reflectionSummaryPolicy;

    public HybridPolicy(FifoPolicy fifoPolicy,
                         LruPolicy lruPolicy,
                         PriorityDecayPolicy priorityDecayPolicy,
                         ReflectionSummaryPolicy reflectionSummaryPolicy) {
        this.fifoPolicy = fifoPolicy;
        this.lruPolicy = lruPolicy;
        this.priorityDecayPolicy = priorityDecayPolicy;
        this.reflectionSummaryPolicy = reflectionSummaryPolicy;
    }

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        if (budget <= 0 || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        var stageBudget = budget / 4;
        var result = new ArrayList<TemporalEntity>();
        var selectedIds = new HashSet<String>();
        var remaining = new ArrayList<>(candidates);

        // 阶段 1: FIFO
        var fifoSelected = fifoPolicy.selectForForgetting(remaining, stageBudget);
        result.addAll(fifoSelected);
        fifoSelected.forEach(e -> selectedIds.add(e.id()));
        remaining.removeIf(e -> selectedIds.contains(e.id()));
        log.debug("Hybrid 阶段 1 (FIFO): 选中={}, 剩余候选={}", fifoSelected.size(), remaining.size());

        // 阶段 2: LRU
        var lruSelected = lruPolicy.selectForForgetting(remaining, stageBudget);
        result.addAll(lruSelected);
        lruSelected.forEach(e -> selectedIds.add(e.id()));
        remaining.removeIf(e -> selectedIds.contains(e.id()));
        log.debug("Hybrid 阶段 2 (LRU): 选中={}, 剩余候选={}", lruSelected.size(), remaining.size());

        // 阶段 3: PriorityDecay
        var decaySelected = priorityDecayPolicy.selectForForgetting(remaining, stageBudget);
        result.addAll(decaySelected);
        decaySelected.forEach(e -> selectedIds.add(e.id()));
        remaining.removeIf(e -> selectedIds.contains(e.id()));
        log.debug("Hybrid 阶段 3 (PriorityDecay): 选中={}, 剩余候选={}", decaySelected.size(), remaining.size());

        // 阶段 4: ReflectionSummary
        var summarySelected = reflectionSummaryPolicy.selectForForgetting(remaining, stageBudget);
        result.addAll(summarySelected);
        log.debug("Hybrid 阶段 4 (ReflectionSummary): 选中={}", summarySelected.size());

        // 确保总数不超过预算
        if (result.size() > budget) {
            return List.copyOf(result.subList(0, budget));
        }
        return List.copyOf(result);
    }

    @Override
    public String name() {
        return "Hybrid";
    }
}
