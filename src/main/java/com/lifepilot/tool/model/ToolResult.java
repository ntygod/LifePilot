package com.lifepilot.tool.model;

import jakarta.annotation.Nullable;
import lombok.Builder;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 工具执行结果 — 结构化信封。
 *
 * <p>所有工具调用（无论成功或失败）都返回 ToolResult。
 * 使用 record 而非异常来表示失败，因为工具调用失败是正常的业务场景。</p>
 *
 * <p>状态模型使用 {@link ToolResultStatus} 枚举替代布尔值，
 * 支持表达中间状态（部分成功、限流）。</p>
 *
 * <p>{@code artifacts} 字段（2026-05-17 引入）承载工具执行后产生的文件产物，
 * 对齐 LangChain {@code ToolMessage.artifact} 概念：作为工具执行结果中
 * 「不喂回 LLM、但需要被下游程序感知」的产物载体，让 Agent 主循环把它升级为
 * 会话级 {@code session_artifacts} 一等公民。既有工厂方法默认 {@code List.of()},
 * 不破坏既有调用点。</p>
 *
 * @param status    执行状态
 * @param data      结构化数据（成功时非空）
 * @param error     错误信息（失败时非空）
 * @param meta      执行元信息
 * @param artifacts 文件产物列表（无产物时为 {@link List#of()}）
 * @author zsg
 * @since 2026-02-24
 */
@Builder(toBuilder = true)
public record ToolResult(
        ToolResultStatus status,
        Map<String, Object> data,
        @Nullable String error,
        ToolResultMeta meta,
        List<ToolArtifact> artifacts
) {

    /**
     * 紧凑构造器：artifacts 为 null 时回退 {@code List.of()} 并做防御性拷贝。
     */
    public ToolResult {
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
    }

    /**
     * 是否成功 — 向后兼容便捷方法，委托 {@link ToolResultStatus#isSuccess()}。
     */
    public boolean ok() {
        return status.isSuccess();
    }

    /**
     * 是否成功 — 语义等价于 {@link #ok()}。
     */
    public boolean isSuccess() {
        return status.isSuccess();
    }

    // ==================== 成功工厂方法 ====================

    /** 创建成功结果。 */
    public static ToolResult success(Map<String, Object> data, ToolResultMeta meta) {
        return new ToolResult(ToolResultStatus.SUCCESS, Map.copyOf(data), null, meta, List.of());
    }

    /**
     * 创建携带文件产物的成功结果。
     *
     * <p>工具执行器（{@code FileWriteToolExecutor} / {@code ShellExecToolExecutor} /
     * {@code CodeExecuteToolExecutor} 等）在工具产生文件后调用本方法，让 Agent 主循环
     * 把产物升级为 {@code session_artifacts} 一等公民。</p>
     *
     * @param data      结构化数据
     * @param meta      执行元信息
     * @param artifacts 文件产物列表；调用方应已应用 {@code ArtifactFilter} 与
     *                  workspace 白名单校验
     */
    public static ToolResult success(Map<String, Object> data, ToolResultMeta meta,
                                     List<ToolArtifact> artifacts) {
        return new ToolResult(ToolResultStatus.SUCCESS, Map.copyOf(data), null, meta,
                artifacts != null ? List.copyOf(artifacts) : List.of());
    }

    /** 创建简单成功结果（无元信息，用于测试）。 */
    public static ToolResult success(Map<String, Object> data) {
        return new ToolResult(ToolResultStatus.SUCCESS, Map.copyOf(data), null, ToolResultMeta.empty(), List.of());
    }

    // ==================== 失败工厂方法 ====================

    /** 创建失败结果。 */
    public static ToolResult error(String message, ToolResultMeta meta) {
        return new ToolResult(ToolResultStatus.ERROR, Map.of(), message, meta, List.of());
    }

    /** 创建简单失败结果（无元信息，用于测试）。 */
    public static ToolResult error(String message) {
        return new ToolResult(ToolResultStatus.ERROR, Map.of(), message, ToolResultMeta.empty(), List.of());
    }

    // ==================== 瞬态失败工厂方法 ====================

    /**
     * 创建瞬态失败结果 — 可识别的临时故障，Agent 循环可自动重试。
     *
     * <p>仅用于明确可识别的瞬态场景（网络超时、HTTP 502/503、服务暂不可用）。
     * 不确定是否瞬态的 generic Exception 应使用 {@link #error(String)}。</p>
     *
     * @param message 失败描述
     * @param meta 执行元信息
     */
    public static ToolResult transientError(String message, ToolResultMeta meta) {
        return new ToolResult(ToolResultStatus.TRANSIENT_ERROR, Map.of(), message, meta, List.of());
    }

    /** 创建简单瞬态失败结果（无元信息）。 */
    public static ToolResult transientError(String message) {
        return new ToolResult(ToolResultStatus.TRANSIENT_ERROR, Map.of(), message, ToolResultMeta.empty(), List.of());
    }

    // ==================== 部分成功工厂方法 ====================

    /**
     * 创建部分成功结果 — data 包含已成功部分，error 包含失败描述。
     *
     * @param data 已成功的结构化数据
     * @param error 失败部分的描述
     * @param meta 执行元信息
     */
    public static ToolResult partialSuccess(Map<String, Object> data, String error, ToolResultMeta meta) {
        return new ToolResult(ToolResultStatus.PARTIAL_SUCCESS, Map.copyOf(data), error, meta, List.of());
    }

    /**
     * 创建简单部分成功结果（无元信息，用于测试）。
     *
     * @param data 已成功的结构化数据
     * @param error 失败部分的描述
     */
    public static ToolResult partialSuccess(Map<String, Object> data, String error) {
        return new ToolResult(ToolResultStatus.PARTIAL_SUCCESS, Map.copyOf(data), error, ToolResultMeta.empty(), List.of());
    }

    // ==================== 限流工厂方法 ====================

    /**
     * 创建限流结果 — data 中包含 {@code retryAfterMs} 字段。
     *
     * @param message 限流描述
     * @param retryAfter 建议的重试等待时间
     * @param meta 执行元信息
     */
    public static ToolResult rateLimited(String message, Duration retryAfter, ToolResultMeta meta) {
        return new ToolResult(ToolResultStatus.RATE_LIMITED,
                Map.of("retryAfterMs", retryAfter.toMillis()), message, meta, List.of());
    }

    /**
     * 创建简单限流结果（无元信息，用于测试）。
     *
     * @param message 限流描述
     * @param retryAfter 建议的重试等待时间
     */
    public static ToolResult rateLimited(String message, Duration retryAfter) {
        return new ToolResult(ToolResultStatus.RATE_LIMITED,
                Map.of("retryAfterMs", retryAfter.toMillis()), message, ToolResultMeta.empty(), List.of());
    }

    /** 获取数据中的指定字段。 */
    @SuppressWarnings("unchecked")
    public <T> T getData(String key) {
        return (T) data.get(key);
    }
}
