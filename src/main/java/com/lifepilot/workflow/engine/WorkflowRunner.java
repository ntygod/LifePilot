package com.lifepilot.workflow.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工作流异步执行器 — 在 Virtual Thread 上执行 DAG 调度循环。
 *
 * <p>提供异步非阻塞的工作流执行入口，由 {@link WorkflowCommandService} 和
 * {@link WakeupScheduler} 调用。内部使用 Java 22 Virtual Thread 实现轻量级并发。
 *
 * @author zsg
 * @since 2026-03-10
 */
public class WorkflowRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunner.class);

    private final WorkflowEngine engine;

    public WorkflowRunner(WorkflowEngine engine) {
        this.engine = engine;
    }

    /**
     * 提交异步执行：在新的 Virtual Thread 上启动 DAG 执行。
     *
     * <p>执行过程中的异常由 {@link WorkflowEngine#executeFromInstance(String)} 内部捕获
     * 并转换为 FAILED 状态，不会传播到调用方。
     *
     * @param instanceId 工作流实例 ID
     */
    public void submitAsync(String instanceId) {
        Thread.ofVirtual()
                .name("workflow-exec-" + instanceId)
                .start(() -> {
                    try {
                        log.info("异步执行工作流: instanceId={}", instanceId);
                        engine.executeFromInstance(instanceId);
                    } catch (Exception e) {
                        log.error("异步执行工作流异常: instanceId={}, error={}", instanceId, e.getMessage(), e);
                    }
                });
    }

    /**
     * 提交异步恢复执行：在新的 Virtual Thread 上从阻塞状态恢复 DAG 执行。
     *
     * <p>执行过程中的异常由 {@link WorkflowEngine#resumeFromBlocked(String)} 内部捕获
     * 并转换为 FAILED 状态，不会传播到调用方。
     *
     * @param instanceId 工作流实例 ID
     */
    public void submitAsyncResume(String instanceId) {
        Thread.ofVirtual()
                .name("workflow-resume-" + instanceId)
                .start(() -> {
                    try {
                        log.info("异步恢复工作流: instanceId={}", instanceId);
                        engine.resumeFromBlocked(instanceId);
                    } catch (Exception e) {
                        log.error("异步恢复工作流异常: instanceId={}, error={}", instanceId, e.getMessage(), e);
                    }
                });
    }
}
