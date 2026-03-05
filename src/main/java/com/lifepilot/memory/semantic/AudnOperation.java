package com.lifepilot.memory.semantic;

/**
 * AUDN 操作类型枚举 — 定义实时实体提取的四种操作。
 *
 * <p>借鉴 Mem0 AUDN 模式：LLM 对每条信息判断应执行的操作类型。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
public enum AudnOperation {
    /** 新增实体。 */
    ADD,
    /** 更新已有实体。 */
    UPDATE,
    /** 删除（标记为非当前）已有实体。 */
    DELETE,
    /** 无操作，跳过。 */
    NOOP
}
