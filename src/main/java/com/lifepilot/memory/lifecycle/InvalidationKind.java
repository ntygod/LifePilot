package com.lifepilot.memory.lifecycle;

/**
 * 源失效方式。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum InvalidationKind {
    /** 源对象被物理删除。 */
    DELETED,
    /** 源对象被归档（仍存在但逻辑失效）。 */
    ARCHIVED,
    /** 源对象内容发生显著变化。 */
    CONTENT_CHANGED
}
