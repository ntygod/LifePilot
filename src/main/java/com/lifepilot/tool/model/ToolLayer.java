package com.lifepilot.tool.model;

/**
 * 工具层次枚举。
 *
 * <p>定义两层工具架构的优先级。同名工具冲突时，
 * 高优先级层覆盖低优先级层。</p>
 *
 * <p>原三层架构中的 SKILL_DECLARATIVE 层已在渐进式披露重构中移除：
 * Skill 不再注册为独立工具，而是通过 load_skill / generate_skill
 * 两个 BuiltinTool 按需加载。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public enum ToolLayer {

    /** MCP 外部工具 — 优先级较低（1）。 */
    MCP_EXTERNAL(1),

    /** Java 原生工具 — 优先级最高（2）。 */
    JAVA_NATIVE(2);

    private final int priority;

    ToolLayer(int priority) {
        this.priority = priority;
    }

    /**
     * 获取优先级数值。数值越大优先级越高。
     *
     * @return 优先级数值
     */
    public int priority() {
        return priority;
    }

    /**
     * 判断当前层是否优先于另一层。
     *
     * @param other 另一层
     * @return 如果当前层优先级更高，返回 true
     */
    public boolean overrides(ToolLayer other) {
        return this.priority > other.priority;
    }
}
