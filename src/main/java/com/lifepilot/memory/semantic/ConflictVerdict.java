package com.lifepilot.memory.semantic;

import jakarta.annotation.Nullable;

/**
 * LLM 冲突裁决结果 — {@link ConflictResolutionService} 调 LLM 后解析得到的结构化判定。
 *
 * <p>三种 verdict：
 * <ul>
 *     <li>{@link Kind#REPLACE} — 新记忆否定或覆盖旧记忆（事实修正），老实体转入
 *         {@code SUPERSEDED}；</li>
 *     <li>{@link Kind#COEXIST} — 两者语义并列不冲突（如"喜欢摇滚" + "喜欢爵士"），
 *         无状态变化；</li>
 *     <li>{@link Kind#TIMELINE} — 时间线上的状态演化，新实体承接旧实体的 succeeded_by
 *         链，老实体转入 {@code SUPERSEDED}。</li>
 * </ul>
 *
 * @param verdict   裁决类型
 * @param targetId  REPLACE / TIMELINE 指向的旧实体 ID；COEXIST 可为 null
 * @param rationale 裁决理由（给用户 / 给审计）
 *
 * @author zsg
 * @since 2026-04-23
 */
public record ConflictVerdict(
        Kind verdict,
        @Nullable String targetId,
        String rationale
) {

    /** 裁决类型枚举 — 与 Prompt 输出约束严格对齐。 */
    public enum Kind {
        REPLACE,
        COEXIST,
        TIMELINE
    }
}
