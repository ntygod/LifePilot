package com.lifepilot.agent.learning.trace;

import com.lifepilot.observability.trace.TraceRecorder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * 学习轨迹监听注册器 — 启动时把 {@link AgentTraceWriter} 挂到
 * {@link TraceRecorder#onTraceEnd}，使每次 ReAct 执行结束后异步落库学习轨迹。
 *
 * <p>持有订阅句柄，销毁时取消监听，避免泄漏。</p>
 *
 * @author zsg
 * @since 2026-06-06
 */
public class AgentTraceListenerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceListenerRegistrar.class);

    private final TraceRecorder traceRecorder;
    private final AgentTraceWriter agentTraceWriter;
    private AutoCloseable subscription;

    public AgentTraceListenerRegistrar(TraceRecorder traceRecorder, AgentTraceWriter agentTraceWriter) {
        this.traceRecorder = Objects.requireNonNull(traceRecorder, "traceRecorder 不能为空");
        this.agentTraceWriter = Objects.requireNonNull(agentTraceWriter, "agentTraceWriter 不能为空");
    }

    @PostConstruct
    public void register() {
        this.subscription = traceRecorder.onTraceEnd(agentTraceWriter::persist);
        log.info("学习轨迹: 已注册 onTraceEnd 监听，执行结束后落库 agent_traces");
    }

    @PreDestroy
    public void unregister() throws Exception {
        if (subscription != null) {
            subscription.close();
        }
    }
}
