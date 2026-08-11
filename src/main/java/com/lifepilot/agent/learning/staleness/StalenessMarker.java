package com.lifepilot.agent.learning.staleness;

import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 把识别出的过时邻居从 ACTIVE 迁移到 STALE_CANDIDATE。
 *
 * <p>reason 字段统一格式化为 {@code "stale-by:<triggeringEntityId>"}，便于审计追溯。
 * 对已处于终态或非 ACTIVE 的邻居静默跳过，不抛异常。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class StalenessMarker {

    private static final Logger log = LoggerFactory.getLogger(StalenessMarker.class);

    private final SemanticMemory semanticMemory;

    public StalenessMarker(SemanticMemory semanticMemory) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
    }

    /**
     * @return 成功标记为 STALE_CANDIDATE 的邻居数
     */
    public int markAsStale(String triggeringEntityId, List<TemporalEntity> neighbors) {
        String triggerId = requireCleanText(triggeringEntityId, "触发实体 ID 不能为空");
        Objects.requireNonNull(neighbors, "过时邻居列表不能为空");
        if (neighbors.isEmpty()) return 0;
        int marked = 0;
        String reason = "stale-by:" + triggerId;
        for (TemporalEntity neighbor : neighbors) {
            Objects.requireNonNull(neighbor, "过时邻居不能为空");
            String neighborId = requireCleanText(neighbor.id(), "过时邻居 ID 不能为空");
            LifecycleState state = neighbor.lifecycleState();
            if (state == null) {
                throw new IllegalStateException("过时邻居生命周期不能为空: id=" + neighborId);
            }
            if (!state.canTransitionTo(LifecycleState.STALE_CANDIDATE)) {
                log.debug("staleness: 跳过非 ACTIVE 邻居 id={}, state={}",
                        neighborId, state);
                continue;
            }
            semanticMemory.updateLifecycleState(
                    neighborId, LifecycleState.STALE_CANDIDATE,
                    reason, ChangeSource.LLM_SEMANTIC);
            marked++;
        }
        return marked;
    }

    private static String requireCleanText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(message + "，且不能包含首尾空白: " + value);
        }
        return value;
    }
}
