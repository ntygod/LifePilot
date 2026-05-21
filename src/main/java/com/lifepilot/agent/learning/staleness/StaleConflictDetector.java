package com.lifepilot.agent.learning.staleness;

import com.lifepilot.memory.store.entity.TemporalEntity;

import java.util.List;

/**
 * 识别与新写入实体形成语义冲突的邻居，用于后续标记为 STALE_CANDIDATE。
 *
 * @author zsg
 * @since 2026-05-09
 */
public interface StaleConflictDetector {

    /**
     * 给定一条新写入的实体，返回可能过时的邻居实体列表。
     *
     * @param newEntity 新写入或更新的实体
     * @return 0..N 个邻居；空列表表示没有可识别的过时邻居
     */
    List<TemporalEntity> findStaleNeighbors(TemporalEntity newEntity);
}
