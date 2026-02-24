package com.lifepilot.memory.episodic;

/**
 * 压缩层级枚举 — 定义对话内容的渐进压缩层级。
 *
 * <p>压缩是单向不可逆过程：ORIGINAL → SUMMARY → KEYPOINTS → ARCHIVED。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public enum CompressionLevel {

    /** 原文 — 完整保留，未经任何压缩。 */
    ORIGINAL(0),

    /** 摘要 — LLM 生成的对话摘要，保留主要信息和决策。 */
    SUMMARY(1),

    /** 要点 — 仅保留关键决策点和结论，高度浓缩。 */
    KEYPOINTS(2),

    /** 归档 — 仅保留元数据，内容已归档。 */
    ARCHIVED(3);

    private final int level;

    CompressionLevel(int level) {
        this.level = level;
    }

    /** 返回压缩层级对应的整数值。 */
    public int level() {
        return level;
    }

    /**
     * 根据整数值返回对应的压缩层级枚举。
     *
     * @param level 整数值
     * @return 对应的枚举值
     * @throws IllegalArgumentException 如果整数值无效
     */
    public static CompressionLevel fromLevel(int level) {
        for (var cl : values()) {
            if (cl.level == level) return cl;
        }
        throw new IllegalArgumentException("无效压缩层级: " + level);
    }
}
