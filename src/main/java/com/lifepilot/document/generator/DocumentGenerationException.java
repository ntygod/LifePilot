package com.lifepilot.document.generator;

/**
 * 文档生成失败异常。
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentGenerationException extends RuntimeException {
    public DocumentGenerationException(String message, Throwable cause) {
        super(message, cause);
    }

    public DocumentGenerationException(String message) {
        super(message);
    }
}
