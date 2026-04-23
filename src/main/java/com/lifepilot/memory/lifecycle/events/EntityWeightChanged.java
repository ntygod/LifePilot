package com.lifepilot.memory.lifecycle.events;

import com.lifepilot.memory.lifecycle.WeightSource;

/**
 * 实体权重变化事件（反馈 / 有效性 / 质量否定）。
 *
 * @author zsg
 * @since 2026-04-23
 */
public record EntityWeightChanged(
    String entityId,
    double delta,
    double cumulativeScore,
    WeightSource source
) {}
