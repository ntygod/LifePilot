package com.lifepilot.knowledge.exception;

/**
 * 重复文档异常 — 当导入的文档与同一知识库中已有文档内容重复时抛出。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DuplicateDocumentException extends RuntimeException {

    /**
     * 构造重复文档异常。
     *
     * @param message 异常描述信息
     */
    public DuplicateDocumentException(String message) {
        super(message);
    }
}
