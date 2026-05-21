package com.lifepilot.memory.retrieval.orchestrator;

import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 统一检索编排器 — {@code memory-system.md §7 Phase H} 的入口实现。
 *
 * <p>流程：
 * <ol>
 *   <li>{@link QueryPlanner#plan} 按 intent 选择 source</li>
 *   <li>顺序调用每个 source（本地场景控制并发复杂度，不并行）</li>
 *   <li>合并所有 items → 按 score 降序 → 取 topK</li>
 *   <li>返回 {@link EvidenceBundle}，包含参与的 source 名称和总延迟</li>
 * </ol>
 * </p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class RetrievalOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RetrievalOrchestrator.class);

    private final QueryPlanner planner;
    private final MemoryRetrievalProperties properties;

    public RetrievalOrchestrator(QueryPlanner planner, MemoryRetrievalProperties properties) {
        this.planner = Objects.requireNonNull(planner);
        this.properties = Objects.requireNonNull(properties);
    }

    public EvidenceBundle retrieve(String query) {
        return retrieve(query, null, properties.getOrchestrator().getDefaultTopK());
    }

    public EvidenceBundle retrieve(String query, @Nullable RetrievalIntent intent, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return EvidenceBundle.empty(query);
        }
        long startNanos = System.nanoTime();
        RetrievalIntent effective = intent != null ? intent : RetrievalIntent.GENERAL;
        List<SourceAdapter> adapters = planner.plan(query, effective);
        int perSourceTopK = Math.max(1, properties.getOrchestrator().getPerSourceTopK());

        List<EvidenceItem> all = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        for (SourceAdapter adapter : adapters) {
            try {
                List<EvidenceItem> items = adapter.retrieve(query, perSourceTopK);
                if (items == null || items.isEmpty()) continue;
                all.addAll(items);
                sources.add(adapter.name());
            } catch (Exception e) {
                log.debug("RetrievalOrchestrator: adapter={} 失败: {}", adapter.name(), e.getMessage());
            }
        }

        // 按 score 降序，相同分按 confidence 降序
        all.sort(Comparator
                .comparingDouble(EvidenceItem::score).reversed()
                .thenComparing(Comparator.comparingDouble(EvidenceItem::confidence).reversed()));
        List<EvidenceItem> top = all.stream().limit(topK).toList();

        long latency = (System.nanoTime() - startNanos) / 1_000_000L;
        log.debug("RetrievalOrchestrator: query='{}', intent={}, sources={}, returned={}, latency={}ms",
                query, effective, sources, top.size(), latency);
        return new EvidenceBundle(query, effective.name(), top, latency, sources,
                Map.of("totalCollected", all.size()));
    }
}
