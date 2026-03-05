package com.lifepilot.observability.trace;

import jakarta.annotation.Nullable;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * 追踪记录器接口 — 定义 Trace 的生命周期管理。
 *
 * @author zsg
 * @since 2026-02-27
 */
public interface TraceRecorder {

    /**
     * 开始一次追踪。
     *
     * @param traceId   追踪 ID
     * @param sessionId 会话 ID
     * @param goal      用户目标
     * @return 追踪上下文
     */
    TraceContext startTrace(String traceId, String sessionId, String goal);

    /**
     * 开始一次追踪（带元数据）。
     *
     * @param traceId   追踪 ID
     * @param sessionId 会话 ID
     * @param goal      用户目标
     * @param metadata  元数据
     * @return 追踪上下文
     */
    TraceContext startTrace(String traceId, String sessionId, String goal, TraceMetadata metadata);

    /**
     * 记录一个追踪步骤。
     *
     * @param context 追踪上下文
     * @param step    追踪步骤
     */
    void recordStep(TraceContext context, TraceStep step);

    /**
     * 结束追踪并构建 TraceRecord。
     *
     * @param context           追踪上下文
     * @param finalOutput       最终输出（可为 null）
     * @param success           是否成功
     * @param errorMessage      错误信息（可为 null）
     * @param terminationReason 终止原因（可为 null）
     * @return 完整的追踪记录
     */
    TraceRecord endTrace(TraceContext context, @Nullable String finalOutput,
                         boolean success, @Nullable String errorMessage,
                         @Nullable String terminationReason);

    /**
     * 注册步骤回调监听器（实时事件）。
     *
     * @param listener 回调函数
     * @return 取消订阅句柄（调用 close() 取消监听）
     */
    AutoCloseable onStep(Consumer<TraceStepEvent> listener);

    /**
     * 注册 Trace 结束回调监听器（实时事件）。
     *
     * @param listener 回调函数
     * @return 取消订阅句柄（调用 close() 取消监听）
     */
    AutoCloseable onTraceEnd(Consumer<TraceRecord> listener);

    /**
     * 获取当前线程/虚拟线程绑定的追踪上下文。
     *
     * @return 追踪上下文（如果存在）
     */
    Optional<TraceContext> currentContext();
}
