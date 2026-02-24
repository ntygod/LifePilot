package com.lifepilot.llm;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * LLM 路由失败异常。
 *
 * <p>当指定场景的所有候选 Provider 都不可用时抛出。
 *
 * @author zsg
 * @since 2026-02-24
 */
public class LlmUnavailableException extends RuntimeException {

    private final String scene;
    private final List<String> attemptedProviders;

    /**
     * 构造路由失败异常。
     *
     * @param message            异常消息
     * @param scene              调用场景
     * @param attemptedProviders 已尝试的 Provider ID 列表
     */
    public LlmUnavailableException(String message, String scene,
                                   List<String> attemptedProviders) {
        this(message, scene, attemptedProviders, null);
    }

    /**
     * 构造路由失败异常（含异常链）。
     *
     * @param message            异常消息
     * @param scene              调用场景
     * @param attemptedProviders 已尝试的 Provider ID 列表
     * @param cause              原始异常
     */
    public LlmUnavailableException(String message, String scene,
                                   List<String> attemptedProviders,
                                   @Nullable Throwable cause) {
        super(message, cause);
        this.scene = scene;
        this.attemptedProviders = List.copyOf(attemptedProviders);
    }

    /**
     * 获取调用场景。
     *
     * @return 场景名称
     */
    public String scene() {
        return scene;
    }

    /**
     * 获取已尝试的 Provider ID 列表（不可变）。
     *
     * @return Provider ID 列表
     */
    public List<String> attemptedProviders() {
        return attemptedProviders;
    }
}
