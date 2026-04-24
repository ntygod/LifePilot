package com.lifepilot.project.exception;

/**
 * 项目不存在异常。
 *
 * <p>当按 id 查找项目但不存在时抛出，由全局异常处理器映射为 404 Not Found。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ProjectNotFoundException extends RuntimeException {

    /**
     * 构造项目不存在异常。
     *
     * @param id 项目 id
     */
    public ProjectNotFoundException(String id) {
        super("项目不存在：" + id);
    }
}
