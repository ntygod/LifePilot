package com.lifepilot.memory.eval.probe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 召回结果采集器。
 *
 * <p>记录每次 {@code memory.search / memory.recall} 调用的上下文：查询、返回实体、
 * ground truth、是否命中。供后续命中率计算。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class RetrievalProbe {

    private final ConcurrentLinkedQueue<Record> records = new ConcurrentLinkedQueue<>();

    public void record(String query,
                       Collection<String> returnedIds,
                       Collection<String> groundTruthIds) {
        Set<String> truth = groundTruthIds == null ? Set.of() : Set.copyOf(groundTruthIds);
        Set<String> returned = returnedIds == null ? Set.of() : Set.copyOf(returnedIds);
        boolean hit = !truth.isEmpty() && returned.stream().anyMatch(truth::contains);
        records.offer(new Record(Instant.now(), query, returned, truth, hit));
    }

    /** 命中率（有 ground truth 的记录中，返回结果包含任一 truth 的比例）。 */
    public float hitRate() {
        int withTruth = 0;
        int hits = 0;
        for (Record r : records) {
            if (r.groundTruthIds().isEmpty()) continue;
            withTruth++;
            if (r.hit()) hits++;
        }
        return withTruth == 0 ? 0f : (float) hits / withTruth;
    }

    public int recordCount() {
        return records.size();
    }

    public List<Record> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    public void reset() {
        records.clear();
    }

    public record Record(
            Instant timestamp,
            String query,
            Set<String> returnedIds,
            Set<String> groundTruthIds,
            boolean hit
    ) {}
}
