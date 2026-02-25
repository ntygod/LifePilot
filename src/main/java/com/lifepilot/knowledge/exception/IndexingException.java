package com.lifepilot.knowledge.exception;

/**
 * 索引构建异常 — 当向量索引或 FTS5 索引构建失败时抛出。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class IndexingException extends RuntimeException {

    /**
     * 构造索引构建异常。
     *
     * @param message 异常描述信息
     */
    public IndexingException(String message) {
        super(message);
    }

    /**
     * 构造索引构建异常（含原因）。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public IndexingException(String message, Throwable cause) {
        super(message, cause);
    }
}
