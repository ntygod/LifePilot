package com.lifepilot.agent.learning.staleness;

import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
        this.semanticMemory = semanticMemory;
    }

    /**
     * @return 成功标记为 STALE_CANDIDATE 的邻居数
     */
    public int markAsStale(String triggeringEntityId, List<TemporalEntity> neighbors) {
        if (neighbors == null || neighbors.isEmpty()) return 0;
        int marked = 0;
        String reason = "stale-by:" + (triggeringEntityId == null ? "unknown" : triggeringEntityId);
        for (TemporalEntity neighbor : neighbors) {
            if (neighbor == null) continue;
            if (neighbor.lifecycleState() == null
                    || !neighbor.lifecycleState().canTransitionTo(LifecycleState.STALE_CANDIDATE)) {
                log.debug("staleness: 跳过非 ACTIVE 邻居 id={}, state={}",
                        neighbor.id(), neighbor.lifecycleState());
                continue;
            }
            try {
                semanticMemory.updateLifecycleState(
                        neighbor.id(), LifecycleState.STALE_CANDIDATE,
                        reason, ChangeSource.LLM_SEMANTIC);
                marked++;
            } catch (RuntimeException e) {
                log.warn("staleness: 标记邻居失败 id={}, err={}", neighbor.id(), e.getMessage());
            }
        }
        return marked;
    }
}
