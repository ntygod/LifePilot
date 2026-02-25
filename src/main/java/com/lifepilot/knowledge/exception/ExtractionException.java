package com.lifepilot.knowledge.exception;

/**
 * 知识提取异常 — 当从文档分块中提取实体或关系失败时抛出。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class ExtractionException extends RuntimeException {

    /**
     * 构造知识提取异常。
     *
     * @param message 异常描述信息
     */
    public ExtractionException(String message) {
        super(message);
    }

    /**
     * 构造知识提取异常（含原因）。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
