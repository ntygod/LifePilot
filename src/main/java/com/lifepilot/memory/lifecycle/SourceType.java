package com.lifepilot.memory.lifecycle;

/**
 * 记忆来源对象类型。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum SourceType {
    /** 文档（document workspace）。 */
    DOCUMENT,
    /** 知识库（knowledge base）。 */
    KNOWLEDGE_BASE,
    /** 会话（预留，当前未由事件生产者使用）。 */
    SESSION
}
