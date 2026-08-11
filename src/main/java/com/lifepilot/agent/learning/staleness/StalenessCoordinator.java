package com.lifepilot.agent.learning.staleness;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Staleness 流水编排：Detector → Marker → RefreshService。
 *
 * <p>通常在 {@code SemanticMemory.upsertWithConflictDetection} 的 afterCommit 回调中触发，
 * 异步执行（虚拟线程），核心失败直接暴露到线程未捕获异常。</p>
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
        this.detector = Objects.requireNonNull(detector, "detector 不能为空");
        this.marker = Objects.requireNonNull(marker, "marker 不能为空");
        this.refreshService = Objects.requireNonNull(refreshService, "refreshService 不能为空");
        this.config = Objects.requireNonNull(config, "staleness 配置不能为空");
    }

    /**
     * 异步处理。默认使用虚拟线程，不阻塞调用方。
     */
    public void process(TemporalEntity newEntity) {
        if (!config.isEnabled()) return;
        TemporalEntity checked = requireEntity(newEntity);
        Thread.startVirtualThread(() -> run(checked));
    }

    /**
     * 同步处理（测试用；生产路径一般不直接调用）。
     */
    void processSync(TemporalEntity newEntity) {
        if (!config.isEnabled()) return;
        run(requireEntity(newEntity));
    }

    private void run(TemporalEntity newEntity) {
        List<TemporalEntity> stale = Objects.requireNonNull(
                detector.findStaleNeighbors(newEntity),
                "staleness detector 返回结果不能为空");
        if (stale.isEmpty()) {
            log.debug("staleness: 无可疑邻居 entity={}", newEntity.id());
            return;
        }

        int marked = marker.markAsStale(newEntity.id(), stale);
        int refreshed = refreshService.writeRefreshCandidates(newEntity, stale);
        log.info("staleness: entity={} marked={} refreshCandidates={}",
                newEntity.id(), marked, refreshed);
    }

    private static TemporalEntity requireEntity(TemporalEntity entity) {
        Objects.requireNonNull(entity, "新实体不能为空");
        String id = entity.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("新实体 ID 不能为空");
        }
        if (!id.equals(id.trim())) {
            throw new IllegalArgumentException("新实体 ID 不能包含首尾空白: " + id);
        }
        return entity;
    }
}
