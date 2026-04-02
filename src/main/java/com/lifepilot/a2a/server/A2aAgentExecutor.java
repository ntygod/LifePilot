package com.lifepilot.a2a.server;

import com.lifepilot.a2a.model.*;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.multiagent.execution.AgentExecutor;
import com.lifepilot.multiagent.registry.AgentRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A2A 请求执行器。
 *
 * <p>接收 A2aMessage，根据 skillId 路由到对应 AgentDefinition，
 * 通过 AgentExecutor 执行，管理 A2aTask 状态转换。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class A2aAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(A2aAgentExecutor.class);

    private final AgentRegistry agentRegistry;
    private final AgentExecutor agentExecutor;
    private final AgentOrchestrator agentOrchestrator;
    private final A2aTaskStore taskStore;

    private final Counter syncSuccessCounter;
    private final Counter syncFailedCounter;
    private final Counter streamSuccessCounter;
    private final Counter streamFailedCounter;
    private final Timer executionTimer;

    public A2aAgentExecutor(AgentRegistry agentRegistry,
                            AgentExecutor agentExecutor,
                            AgentOrchestrator agentOrchestrator,
                            A2aTaskStore taskStore,
                            MeterRegistry meterRegistry) {
        this.agentRegistry = agentRegistry;
        this.agentExecutor = agentExecutor;
        this.agentOrchestrator = agentOrchestrator;
        this.taskStore = taskStore;

        this.syncSuccessCounter = meterRegistry.counter("a2a.server.messages.total", "method", "sync", "status", "success");
        this.syncFailedCounter = meterRegistry.counter("a2a.server.messages.total", "method", "sync", "status", "failed");
        this.streamSuccessCounter = meterRegistry.counter("a2a.server.messages.total", "method", "stream", "status", "success");
        this.streamFailedCounter = meterRegistry.counter("a2a.server.messages.total", "method", "stream", "status", "failed");
        this.executionTimer = meterRegistry.timer("a2a.server.execution.duration");
    }

    /**
     * 同步执行 A2A 消息请求。
     *
     * @param message A2A 消息
     * @param skillId 目标 Skill ID（可空）
     * @return 执行完成的 A2aTask
     */
    public A2aTask execute(A2aMessage message, @Nullable String skillId) {
        A2aTask task = taskStore.resolveOrCreate(message);
        String taskId = task.id();
        taskStore.updateStatus(taskId, A2aTaskState.WORKING, null);

        return executionTimer.record(() -> {
            try {
                String result = doExecute(extractText(message), taskId, skillId);
                if (result == null) {
                    // doExecute 已设置 FAILED 状态
                    syncFailedCounter.increment();
                    return taskStore.find(taskId).orElseThrow();
                }

                var artifact = new A2aArtifact(
                        UUID.randomUUID().toString(),
                        List.of(new A2aPart.Text(result, null)),
                        null, null);
                taskStore.addArtifact(taskId, artifact);
                taskStore.updateStatus(taskId, A2aTaskState.COMPLETED, null);

                syncSuccessCounter.increment();
                log.info("A2A 任务执行完成: taskId={}, skillId={}", taskId, skillId);
            } catch (Exception e) {
                log.warn("A2A 任务执行异常: taskId={}, error={}", taskId, e.getMessage(), e);
                taskStore.updateStatus(taskId, A2aTaskState.FAILED, "执行异常: " + e.getMessage());
                syncFailedCounter.increment();
            }
            return taskStore.find(taskId).orElseThrow();
        });
    }

    /**
     * 流式执行 A2A 消息请求。
     *
     * <p>在 Virtual Thread 中异步执行，通过 Consumer 回调推送状态更新。</p>
     *
     * @param message  A2A 消息
     * @param skillId  目标 Skill ID（可空）
     * @param listener 状态更新回调
     */
    public void executeStreaming(A2aMessage message,
                                @Nullable String skillId,
                                Consumer<A2aTask> listener) {
        Thread.startVirtualThread(() -> {
            try {
            A2aTask task = taskStore.resolveOrCreate(message);
            String taskId = task.id();
            safeNotify(listener, task);

            taskStore.updateStatus(taskId, A2aTaskState.WORKING, null);
            safeNotify(listener, taskStore.find(taskId).orElseThrow());

            try {
                String result = doExecute(extractText(message), taskId, skillId);
                if (result == null) {
                    streamFailedCounter.increment();
                    safeNotify(listener, taskStore.find(taskId).orElseThrow());
                    return;
                }

                var artifact = new A2aArtifact(
                        UUID.randomUUID().toString(),
                        List.of(new A2aPart.Text(result, null)),
                        null, null);
                taskStore.addArtifact(taskId, artifact);
                safeNotify(listener, taskStore.find(taskId).orElseThrow());

                taskStore.updateStatus(taskId, A2aTaskState.COMPLETED, null);
                safeNotify(listener, taskStore.find(taskId).orElseThrow());

                streamSuccessCounter.increment();
                log.info("A2A 流式任务执行完成: taskId={}, skillId={}", taskId, skillId);
            } catch (Exception e) {
                log.warn("A2A 流式任务执行异常: taskId={}, error={}", taskId, e.getMessage(), e);
                taskStore.updateStatus(taskId, A2aTaskState.FAILED, "执行异常: " + e.getMessage());
                streamFailedCounter.increment();
                safeNotify(listener, taskStore.find(taskId).orElseThrow());
            }
            } catch (Exception outerEx) {
                // 最外层防护：resolveOrCreate 等初始化步骤异常时不让 Virtual Thread 静默终止
                log.error("A2A 流式任务初始化异常: error={}", outerEx.getMessage(), outerEx);
                streamFailedCounter.increment();
            }
        });
    }

    /**
     * 核心执行逻辑 — 根据 skillId 路由到子 Agent 或主 Orchestrator。
     *
     * @return 执行结果文本，skillId 无效时返回 null（已设置 FAILED 状态）
     */
    @Nullable
    private String doExecute(String textContent, String taskId, @Nullable String skillId) {
        if (skillId != null && !skillId.isBlank()) {
            var definition = agentRegistry.find(skillId);
            if (definition.isEmpty()) {
                taskStore.updateStatus(taskId, A2aTaskState.FAILED, "未知 Skill: " + skillId);
                return null;
            }
            var subRequest = new AgentRequest(textContent, taskId, InteractionSource.system("a2a"),
                    null,
                    definition.get().systemPrompt(), definition.get().budget().toAgentBudget(),
                    null, 0, definition.get().preferredProvider(), null, null, null);
            var subResult = agentExecutor.execute(definition.get(), subRequest);
            return subResult.output();
        } else {
            var request = new AgentRequest(textContent, taskId, InteractionSource.system("a2a"));
            var response = agentOrchestrator.run(request);
            return response.content();
        }
    }

    /** 从 A2aMessage 中提取文本内容。 */
    private String extractText(A2aMessage message) {
        var sb = new StringBuilder();
        for (A2aPart part : message.parts()) {
            if (part instanceof A2aPart.Text text) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    /** 安全回调 — 捕获 listener 异常，避免 Virtual Thread 静默终止。 */
    private void safeNotify(Consumer<A2aTask> listener, A2aTask task) {
        try {
            listener.accept(task);
        } catch (Exception e) {
            log.warn("A2A SSE 回调失败: taskId={}, error={}", task.id(), e.getMessage());
        }
    }
}
