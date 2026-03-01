package com.lifepilot.memory.forgetting;

import com.lifepilot.memory.semantic.TemporalEntity;

import java.util.List;

/**
 * FIFO 遗忘策略 — 淘汰超过最大保留天数的实体。
 *
 * <p>按创建时间排序，最早创建的实体优先被遗忘。
 * 具体实现将在后续任务（11.4）中完成。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public final class FifoPolicy implements ForgettingPolicy {

    @Override
    public List<TemporalEntity> selectForForgetting(List<TemporalEntity> candidates, int budget) {
        // TODO: 任务 11.4 实现
        return List.of();
    }

    @Override
    public String name() {
        return "FIFO";
    }
}
