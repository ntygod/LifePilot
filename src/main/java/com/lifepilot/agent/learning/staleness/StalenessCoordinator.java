package com.lifepilot.agent.learning.staleness;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Staleness 流水编排：Detector → Marker → RefreshService。
 *
 * <p>通常在 {@code SemanticMemory.upsertWithConflictDetection} 的 afterCommit 回调中触发，
 * 异步执行（虚拟线程），异常分阶段隔离。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class StalenessCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StalenessCoordinator.class);

    private final StaleConflictDetector detector;
    private final StalenessMarker marker;
    private final NeighborRefreshService refreshService;
    private final AgentLearningProperties.Staleness config;

    public StalenessCoordinator(StaleConflictDetector detector,
                                StalenessMarker marker,
                                NeighborRefreshService refreshService,
                                AgentLearningProperties.Staleness config) {
        this.detector = detector;
        this.marker = marker;
        this.refreshService = refreshService;
        this.config = config;
    }

    /**
     * 异步处理。默认使用虚拟线程，不阻塞调用方。
     */
    public void process(TemporalEntity newEntity) {
        if (!config.isEnabled() || newEntity == null) return;
        Thread.startVirtualThread(() -> safeRun(newEntity));
    }

    /**
     * 同步处理（测试用；生产路径一般不直接调用）。
     */
    void processSync(TemporalEntity newEntity) {
        if (!config.isEnabled() || newEntity == null) return;
        safeRun(newEntity);
    }

    private void safeRun(TemporalEntity newEntity) {
        List<TemporalEntity> stale;
        try {
            stale = detector.findStaleNeighbors(newEntity);
        } catch (RuntimeException e) {
            log.warn("staleness detect 失败: entity={}, err={}", newEntity.id(), e.getMessage());
            return;
        }
        if (stale.isEmpty()) {
            log.debug("staleness: 无可疑邻居 entity={}", newEntity.id());
            return;
        }

        int marked;
        try {
            marked = marker.markAsStale(newEntity.id(), stale);
        } catch (RuntimeException e) {
            log.warn("staleness mark 失败: entity={}, err={}", newEntity.id(), e.getMessage());
            return;
        }

        try {
            int refreshed = refreshService.writeRefreshCandidates(newEntity, stale);
            log.info("staleness: entity={} marked={} refreshCandidates={}",
                    newEntity.id(), marked, refreshed);
        } catch (RuntimeException e) {
            log.warn("staleness refresh 失败: entity={}, err={}", newEntity.id(), e.getMessage());
        }
    }
}
