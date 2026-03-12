package com.lifepilot.llm;

import org.springframework.lang.Nullable;

/**
 * 解析场景级默认 Provider 偏好。
 *
 * @author zsg
 * @since 2026-03-10
 */
public interface LlmRoutingPreferenceResolver {

    /**
     * 返回给定场景优先尝试的 Provider ID。
     *
     * @param scene 场景名
     * @return Provider ID；返回 null 表示走自动路由
     */
    @Nullable
    String preferredProviderForScene(String scene);
}
