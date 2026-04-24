package com.lifepilot.skill.spec;

/**
 * Skill 优先级。影响 catalog 排序。
 *
 * @author zsg
 * @since 2026-04-24
 */
public enum SkillPriority {
    /** 始终优先呈现在 catalog 顶部；用于高频核心 skill。 */
    HIGH,
    /** 默认级别；按 catalog 内部排序规则呈现。 */
    NORMAL,
    /** 低频或实验性 skill；排序靠后。 */
    LOW
}
