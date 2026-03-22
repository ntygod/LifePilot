package com.lifepilot.eval.scenario;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 智能 Mock 工具定义 — 支持参数匹配、错误模拟、条件响应。
 *
 * @param toolId          工具 ID
 * @param behaviors       按条件匹配的行为列表（按顺序匹配，首个命中生效）
 * @param defaultResponse 无匹配时的默认响应 JSON
 * @author zsg
 * @since 2026-03-22
 */
public record MockToolSpec(
        String toolId,
        List<MockBehavior> behaviors,
        String defaultResponse
) {
    public MockToolSpec {
        behaviors = behaviors != null ? List.copyOf(behaviors) : List.of();
    }

    /**
     * Mock 行为定义 — 单条匹配规则。
     *
     * @param parameterPattern 参数正则匹配模式（null 表示匹配所有）
     * @param response         匹配时返回的响应 JSON
     * @param simulateError    是否模拟工具执行错误
     * @param delayMs          模拟延迟（毫秒），0 表示无延迟
     */
    public record MockBehavior(
            @Nullable String parameterPattern,
            String response,
            boolean simulateError,
            int delayMs
    ) {}
}
