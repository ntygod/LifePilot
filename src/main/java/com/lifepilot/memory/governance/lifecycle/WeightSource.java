package com.lifepilot.memory.governance.lifecycle;

/**
 * 实体权重变化来源。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum WeightSource {
    /** 用户显式反馈（点赞/点踩）。 */
    USER_FEEDBACK,
    /** 系统自动有效性评估（EffectivenessTracker）。 */
    EFFECTIVENESS,
    /** 经验质量否定（失败工具链产出的 EXPERIENCE 被标记作废）。 */
    QUALITY_REJECT
}
