package com.lifepilot.memory.governance.lifecycle.events;

import com.lifepilot.memory.governance.lifecycle.WeightSource;

/**
 * 实体权重变化事件（反馈 / 有效性 / 质量否定）。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code delta} 正负号：正值表示权重上升（点赞 / 评估加分），负值表示下降（点踩 / 质量否定）；
 *       服务端计算（{@code newScore - oldScore}），首次写入时退化为 {@code newScore} 本身</li>
 *   <li>{@code cumulativeScore}：调整后的 importance 绝对值（即 {@code oldScore + delta}），
 *       供下游监听器（如 NegativeFeedbackListener）直接与配置阈值比较，无需再查库累加</li>
 *   <li>单位：无量纲浮点数，typical 范围 0.0~1.0；阈值配置
 *       {@code memory.feedback.negative-threshold-score} 默认 -1.0（负向触发 SUPERSEDED 转换）</li>
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
