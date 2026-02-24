package com.lifepilot.knowledge.exception;

/**
 * 文档未找到异常。
 *
 * <p>当根据 ID 查找文档但不存在时抛出此异常。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DocumentNotFoundException extends RuntimeException {

    /**
     * 构造文档未找到异常。
     *
     * @param message 异常描述信息
     */
    public DocumentNotFoundException(String message) {
        super(message);
    }
}
