package com.lifepilot.agent.learning.trace;

import com.lifepilot.observability.trace.TraceRecorder;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 学习轨迹监听注册器 — 启动时把 {@link AgentTraceWriter} 挂到
 * {@link TraceRecorder#onTraceEnd}，使每次 ReAct 执行结束后异步落库学习轨迹。
 *
 * <p>持有订阅句柄，销毁时取消监听，避免泄漏。{@code traceRecorder} 为 null
 * （可观测性未启用）时不注册监听，等价于学习轨迹采集关闭。</p>
 *
 * @author zsg
 * @since 2026-06-06
 */
public class AgentTraceListenerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceListenerRegistrar.class);

    @Nullable
    private final TraceRecorder traceRecorder;
    private final AgentTraceWriter agentTraceWriter;
    private AutoCloseable subscription;

    public AgentTraceListenerRegistrar(@Nullable TraceRecorder traceRecorder, AgentTraceWriter agentTraceWriter) {
        this.traceRecorder = traceRecorder;
        this.agentTraceWriter = agentTraceWriter;
    }

    @PostConstruct
    public void register() {
        if (traceRecorder == null) {
            log.info("学习轨迹: TraceRecorder 不可用，跳过 onTraceEnd 监听注册");
            return;
        }
        this.subscription = traceRecorder.onTraceEnd(agentTraceWriter::persist);
        log.info("学习轨迹: 已注册 onTraceEnd 监听，执行结束后落库 agent_traces");
    }

    @PreDestroy
    public void unregister() {
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception e) {
                log.debug("学习轨迹: 取消监听异常: {}", e.getMessage());
            }
        }
    }
}
