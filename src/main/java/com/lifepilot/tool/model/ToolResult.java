package com.lifepilot.tool.model;

import jakarta.annotation.Nullable;
import lombok.Builder;

import java.util.Map;

/**
 * 工具执行结果 — 结构化信封。
 *
 * <p>所有工具调用（无论成功或失败）都返回 ToolResult。
 * 使用 record 而非异常来表示失败，因为工具调用失败是正常的业务场景。</p>
 *
 * @param ok 是否成功
 * @param data 结构化数据（成功时非空）
 * @param error 错误信息（失败时非空）
 * @param meta 执行元信息
 * @author zsg
 * @since 2026-02-24
 */
@Builder(toBuilder = true)
public record ToolResult(
        boolean ok,
        Map<String, Object> data,
        @Nullable String error,
        ToolResultMeta meta
) {
    /** 创建成功结果。 */
    public static ToolResult success(Map<String, Object> data, ToolResultMeta meta) {
        return new ToolResult(true, Map.copyOf(data), null, meta);
    }

    /** 创建失败结果。 */
    public static ToolResult error(String message, ToolResultMeta meta) {
        return new ToolResult(false, Map.of(), message, meta);
    }

    /** 创建简单成功结果（无元信息，用于测试）。 */
    public static ToolResult success(Map<String, Object> data) {
        return new ToolResult(true, Map.copyOf(data), null, ToolResultMeta.empty());
    }

    /** 创建简单失败结果（无元信息，用于测试）。 */
    public static ToolResult error(String message) {
        return new ToolResult(false, Map.of(), message, ToolResultMeta.empty());
    }

    /** 获取数据中的指定字段。 */
    @SuppressWarnings("unchecked")
    public <T> T getData(String key) {
        return (T) data.get(key);
    }
}
