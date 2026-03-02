package com.lifepilot.a2a.server;

import com.lifepilot.a2a.model.*;
import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.multiagent.execution.AgentExecutor;
import com.lifepilot.multiagent.registry.AgentRegistry;
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
    private final AgentLoop agentLoop;
    private final A2aTaskStore taskStore;

    public A2aAgentExecutor(AgentRegistry agentRegistry,
                            AgentExecutor agentExecutor,
                            AgentLoop agentLoop,
                            A2aTaskStore taskStore) {
        this.agentRegistry = agentRegistry;
        this.agentExecutor = agentExecutor;
        this.agentLoop = agentLoop;
        this.taskStore = taskStore;
    }

    /**
     * 同步执行 A2A 消息请求。
     *
     * @param message A2A 消息
     * @param skillId 目标 Skill ID（可空）
     * @return 执行完成的 A2aTask
     */
    public A2aTask execute(A2aMessage message, @Nullable String skillId) {
        // 检查是否引用已有 Task
        A2aTask task;
        if (message.taskId() != null) {
            var existing = taskStore.find(message.taskId());
            if (existing.isPresent()) {
                task = taskStore.appendHistory(message.taskId(), message);
            } else {
                task = taskStore.create(message);
            }
        } else {
            task = taskStore.create(message);
        }

        String taskId = task.id();
        taskStore.updateStatus(taskId, A2aTaskState.WORKING, null);

        try {
            // 提取文本内容
            String textContent = extractText(message);

            String result;
            if (skillId != null && !skillId.isBlank()) {
                // 路由到指定 Agent
                var definition = agentRegistry.find(skillId);
                if (definition.isEmpty()) {
                    taskStore.updateStatus(taskId, A2aTaskState.FAILED, "未知 Skill: " + skillId);
                    return taskStore.find(taskId).orElseThrow();
                }
                // 使用 AgentExecutor 执行子 Agent（A2A 目前不携带多模态媒体）
                var parentState = AgentState.init(new AgentRequest(textContent, taskId, "a2a"));
                var subResult = agentExecutor.execute(definition.get(), textContent, null, parentState);
                result = subResult.output();
                } else {
                    // 路由到主 AgentLoop（A2A 目前不携带多模态媒体）
                    var request = new AgentRequest(textContent, taskId, "a2a");
                AgentResponse response = agentLoop.run(request);
                result = response.content();
            }

            // 封装 Artifact
            var artifact = new A2aArtifact(
                    UUID.randomUUID().toString(),
                    List.of(new A2aPart.Text(result, null)),
                    null, null);
            taskStore.addArtifact(taskId, artifact);
            taskStore.updateStatus(taskId, A2aTaskState.COMPLETED, null);

            log.info("A2A 任务执行完成: taskId={}, skillId={}", taskId, skillId);

        } catch (Exception e) {
            log.warn("A2A 任务执行异常: taskId={}, error={}", taskId, e.getMessage(), e);
            taskStore.updateStatus(taskId, A2aTaskState.FAILED, "执行异常: " + e.getMessage());
        }

        return taskStore.find(taskId).orElseThrow();
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
            // 创建 Task
            A2aTask task = taskStore.create(message);
            String taskId = task.id();
            listener.accept(task);

            taskStore.updateStatus(taskId, A2aTaskState.WORKING, null);
            listener.accept(taskStore.find(taskId).orElseThrow());

            try {
                String textContent = extractText(message);
                String result;

                if (skillId != null && !skillId.isBlank()) {
                    var definition = agentRegistry.find(skillId);
                    if (definition.isEmpty()) {
                        taskStore.updateStatus(taskId, A2aTaskState.FAILED, "未知 Skill: " + skillId);
                        listener.accept(taskStore.find(taskId).orElseThrow());
                        return;
                    }
                    var parentState = AgentState.init(new AgentRequest(textContent, taskId, "a2a"));
                    var subResult = agentExecutor.execute(definition.get(), textContent, null, parentState);
                    result = subResult.output();
                } else {
                    var request = new AgentRequest(textContent, taskId, "a2a");
                    AgentResponse response = agentLoop.run(request);
                    result = response.content();
                }

                var artifact = new A2aArtifact(
                        UUID.randomUUID().toString(),
                        List.of(new A2aPart.Text(result, null)),
                        null, null);
                taskStore.addArtifact(taskId, artifact);
                listener.accept(taskStore.find(taskId).orElseThrow());

                taskStore.updateStatus(taskId, A2aTaskState.COMPLETED, null);
                listener.accept(taskStore.find(taskId).orElseThrow());

                log.info("A2A 流式任务执行完成: taskId={}, skillId={}", taskId, skillId);

            } catch (Exception e) {
                log.warn("A2A 流式任务执行异常: taskId={}, error={}", taskId, e.getMessage(), e);
                taskStore.updateStatus(taskId, A2aTaskState.FAILED, "执行异常: " + e.getMessage());
                listener.accept(taskStore.find(taskId).orElseThrow());
            }
        });
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
}
