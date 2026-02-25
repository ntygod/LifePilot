package com.lifepilot.media;

/**
 * 文档内容提取异常。
 * <p>
 * 当文档解析失败或不支持的文档格式时抛出。
 *
 * @author zsg
 * @since 2026-07-01
 */
public class DocumentExtractionException extends RuntimeException {

    /**
     * 构造文档提取异常。
     *
     * @param message 异常描述信息
     */
    public DocumentExtractionException(String message) {
        super(message);
    }

    /**
     * 构造文档提取异常（含原因）。
     *
     * @param message 异常描述信息
     * @param cause   原始异常
     */
    public DocumentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
