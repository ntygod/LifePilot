package com.lifepilot.memory.consolidation.association;

/**
 * REM 式联想关联类型。
 *
 * <p>由 {@link AssociationCandidateGenerator} 调用 LLM 推断 seed 与 neighbor 之间的
 * 潜在语义关系。用于后续 L3 relations 主库的候选审计。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public enum AssociationType {
    /** 一般相关（最弱关系，兜底值）。 */
    RELATED_TO,
    /** source 导致 / 促使 target。 */
    CAUSES,
    /** 语义相似 / 类比。 */
    SIMILAR_TO,
    /** source 支持 / 佐证 target。 */
    SUPPORTS,
    /** source 与 target 冲突。 */
    CONTRADICTS
}
