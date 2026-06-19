package com.lifepilot.agent.task.proactive.behavior;

/**
 * 主动行为插件分层 — 按其依赖的记忆层归类，让
 * {@link BehaviorActivationPolicy} 可以基于上下文决定哪些组需要激活。
 *
 * <p>设计原则：每个行为插件显式声明自己 depend 的记忆层；同一层的插件共享激活门，
 * 降低每次心跳"全激活"带来的无谓 detect 开销。</p>
 *
 * <ul>
 *   <li>{@link #FACT_DRIVEN}：依赖 L3 语义实体变化（如用户刚显式否认某偏好、新目标实体落库）。
 *       代表：FollowUp / Insight / MemoryAttention。</li>
 *   <li>{@link #HABIT_DRIVEN}：依赖 L4 PreferenceRule（时段 / 频率类）。
 *       代表：Reminder / Report。</li>
 *   <li>{@link #STANDALONE}：独立触发，不依赖上述两层（如剪贴板意图）。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-05-09
 */
public enum BehaviorLayer {
    FACT_DRIVEN,
    HABIT_DRIVEN,
    STANDALONE
}
