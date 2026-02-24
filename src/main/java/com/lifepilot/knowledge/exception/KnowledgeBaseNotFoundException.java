package com.lifepilot.knowledge.exception;

/**
 * 知识库未找到异常。
 *
 * <p>当根据 ID 查找知识库但不存在时抛出此异常。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class KnowledgeBaseNotFoundException extends RuntimeException {

    /**
     * 构造知识库未找到异常。
     *
     * @param message 异常描述信息
     */
    public KnowledgeBaseNotFoundException(String message) {
        super(message);
    }
}
