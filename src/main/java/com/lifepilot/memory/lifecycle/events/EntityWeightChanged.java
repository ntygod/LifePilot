package com.lifepilot.memory.lifecycle.events;

import com.lifepilot.memory.lifecycle.WeightSource;

/**
 * 实体权重变化事件（反馈 / 有效性 / 质量否定）。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code delta} 正负号：正值表示权重上升（点赞 / 评估加分），负值表示下降（点踩 / 质量否定）</li>
 *   <li>{@code cumulativeScore}：该实体自创建以来所有反馈 delta 的累积值（非 importance 绝对值）</li>
 *   <li>单位：无量纲浮点数，typical 范围 ±1.0 量级；达到阈值（配置 {@code memory.feedback.negative-threshold-score} 默认 -1.0）触发 SUPERSEDED 转换</li>
 * </ul>
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
