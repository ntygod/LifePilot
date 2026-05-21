package com.lifepilot.agent.learning.consolidation.association;

import com.lifepilot.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REM 联想候选持久化编排。
 *
 * <p>对 {@link AssociationCandidateGenerator} 产出的候选做：
 * <ol>
 *   <li>置信度过滤（默认阈值 {@code memory.rem.min-confidence=0.65}）</li>
 *   <li>内存去重（key={@code source:target:type}，窗口 {@code deduplicationWindowHours}）</li>
 *   <li>按当前日期写入 {@link AssociationCandidateStore}</li>
 * </ol>
 * </p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class AssociationConsolidator {

    private static final Logger log = LoggerFactory.getLogger(AssociationConsolidator.class);

    private final MemoryProperties properties;
    private final AssociationCandidateStore store;

    /** dedup 键 → 最近入库时间（内存，线程安全）。 */
    private final Map<String, Instant> dedup = new ConcurrentHashMap<>();

    public AssociationConsolidator(MemoryProperties properties, AssociationCandidateStore store) {
        this.properties = Objects.requireNonNull(properties);
        this.store = Objects.requireNonNull(store);
    }

    /**
     * 过滤去重 + 持久化；返回实际入库数量。
     */
    public int consolidate(List<AssociationCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return 0;
        float minConfidence = properties.getRem().getMinConfidence();
        int windowHours = Math.max(1, properties.getRem().getDeduplicationWindowHours());

        Instant now = Instant.now();
        // 顺便清理过期的 dedup 条目，避免长期运行内存膨胀
        dedup.entrySet().removeIf(e -> Duration.between(e.getValue(), now).toHours() >= windowHours * 4L);

        List<AssociationCandidate> keep = new ArrayList<>();
        for (var c : candidates) {
            if (c.confidence() < minConfidence) continue;
            String key = c.dedupKey();
            Instant last = dedup.get(key);
            if (last != null && Duration.between(last, now).toHours() < windowHours) continue;
            dedup.put(key, now);
            keep.add(c);
        }

        if (keep.isEmpty()) {
            log.debug("REM 联想: 过滤后无入库候选, input={}, minConfidence={}",
                    candidates.size(), minConfidence);
            return 0;
        }
        store.save(LocalDate.now(), keep);
        log.info("REM 联想: 入库 input={}, saved={}", candidates.size(), keep.size());
        return keep.size();
    }
}
