package com.lifepilot.workflow.engine;

import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.model.*;
import com.lifepilot.workflow.model.WorkflowStep.NoopStep;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * WorkflowEngine 崩溃恢复单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class WorkflowEngine崩溃恢复测试 {

    private WorkflowRegistry registry;
    private StepExecutor stepExecutor;
    private ExpressionEngine expressionEngine;
    private WorkflowRepository repository;
    private WorkflowConfigProperties config;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        registry = mock(WorkflowRegistry.class);
        stepExecutor = mock(StepExecutor.class);
        expressionEngine = new ExpressionEngine();
        repository = mock(WorkflowRepository.class);
        config = new WorkflowConfigProperties();
        engine = new WorkflowEngine(registry, stepExecutor, expressionEngine, repository, config);
    }

    // ==================== 辅助方法 ====================

    private WorkflowDefinition 创建简单工作流(String id, WorkflowStep... steps) {
        return WorkflowDefinition.builder()
                .id(id)
                .name("测试工作流-" + id)
                .enabled(true)
                .steps(List.of(steps))
                .build();
    }

    private NoopStep 创建NoopStep(String id) {
        return new NoopStep(id, "Noop-" + id, null);
    }

    private WorkflowInstance 创建中断实例(String instanceId, String workflowId,
                                          WorkflowState state, int stepIndex,
                                          WorkflowContext context) {
        return WorkflowInstance.builder()
                .id(instanceId)
                .workflowId(workflowId)
                .state(state)
                .context(context)
                .currentStepIndex(stepIndex)
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    // ==================== RUNNING 实例恢复 ====================

    @Test
    void RUNNING实例_从currentStepIndex恢复执行() throws Exception {
        // 准备：3 步工作流，实例在 step2（index=1）中断
        var step1 = 创建NoopStep("step1");
        var step2 = 创建NoopStep("step2");
        var step3 = 创建NoopStep("step3");
        var def = 创建简单工作流("wf-1", step1, step2, step3);

        var context = new WorkflowContext();
        context.set("inputs", Map.of("key", "value"));
        var instance = 创建中断实例("inst-running", "wf-1", WorkflowState.RUNNING, 1, context);

        when(repository.findInstancesByState(WorkflowState.RUNNING, WorkflowState.WAITING))
                .thenReturn(List.of(instance));
        when(registry.find("wf-1")).thenReturn(Optional.of(def));
        when(stepExecutor.execute(any(), any(), any())).thenReturn(Map.of("ok", true));

        // 使用 CountDownLatch 等待 Virtual Thread 完成
        var latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            WorkflowInstance updated = invocation.getArgument(0);
            if (updated.state() == WorkflowState.COMPLETED) {
                latch.countDown();
            }
            return null;
        }).when(repository).updateInstance(any());

        engine.recoverInterruptedInstances();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "崩溃恢复应在 5 秒内完成");

        // 验证：从 stepIndex=1 开始执行，step2 和 step3 被执行（2 次）
        verify(stepExecutor, times(2)).execute(any(), any(), any());
        // 验证持久化调用
        verify(repository, atLeast(1)).updateInstance(argThat(inst ->
                inst.state() == WorkflowState.COMPLETED));
    }

    // ==================== WAITING 实例恢复 ====================

    @Test
    void WAITING实例_转换为RUNNING并从下一步恢复() throws Exception {
        // 准备：3 步工作流，实例在 step1（index=0，WaitStep）等待
        var waitStep = new WorkflowStep.WaitStep("wait-1", "等待步骤", 60, null);
        var step2 = 创建NoopStep("step2");
        var step3 = 创建NoopStep("step3");
        var def = 创建简单工作流("wf-wait", waitStep, step2, step3);

        var context = new WorkflowContext();
        var instance = 创建中断实例("inst-waiting", "wf-wait", WorkflowState.WAITING, 0, context);

        when(repository.findInstancesByState(WorkflowState.RUNNING, WorkflowState.WAITING))
                .thenReturn(List.of(instance));
        when(registry.find("wf-wait")).thenReturn(Optional.of(def));
        when(stepExecutor.execute(any(), any(), any())).thenReturn(Map.of("ok", true));

        var latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            WorkflowInstance updated = invocation.getArgument(0);
            if (updated.state() == WorkflowState.COMPLETED) {
                latch.countDown();
            }
            return null;
        }).when(repository).updateInstance(any());

        engine.recoverInterruptedInstances();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "崩溃恢复应在 5 秒内完成");

        // WAITING 实例从 currentStepIndex+1=1 开始，执行 step2 和 step3（2 次）
        verify(stepExecutor, times(2)).execute(any(), any(), any());
        // 验证 WAITING→RUNNING 转换被持久化
        verify(repository, atLeast(1)).updateInstance(argThat(inst ->
                inst.state() == WorkflowState.RUNNING));
    }

    // ==================== 上下文损坏 ====================

    @Test
    void 上下文损坏_标记FAILED并记录原因() throws Exception {
        var step1 = 创建NoopStep("step1");
        var def = 创建简单工作流("wf-corrupt", step1);

        // context 为 null 模拟损坏
        var instance = WorkflowInstance.builder()
                .id("inst-corrupt")
                .workflowId("wf-corrupt")
                .state(WorkflowState.RUNNING)
                .context(null)
                .currentStepIndex(0)
                .startedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(repository.findInstancesByState(WorkflowState.RUNNING, WorkflowState.WAITING))
                .thenReturn(List.of(instance));

        var latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            WorkflowInstance updated = invocation.getArgument(0);
            if (updated.state() == WorkflowState.FAILED) {
                latch.countDown();
            }
            return null;
        }).when(repository).updateInstance(any());

        engine.recoverInterruptedInstances();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "崩溃恢复应在 5 秒内完成");

        verify(repository).updateInstance(argThat(inst ->
                inst.state() == WorkflowState.FAILED
                && "崩溃恢复失败：上下文数据损坏".equals(inst.failureReason())));
        // 不应执行任何步骤
        verify(stepExecutor, never()).execute(any(), any(), any());
    }

    // ==================== 恢复开关禁用 ====================

    @Test
    void crashRecoveryEnabled为false_跳过恢复() throws Exception {
        config.setCrashRecoveryEnabled(false);

        engine.recoverInterruptedInstances();

        // 等待一小段时间确认没有异步操作
        Thread.sleep(200);

        // 不应查询中断实例
        verify(repository, never()).findInstancesByState(any());
        verify(stepExecutor, never()).execute(any(), any(), any());
    }

    // ==================== 工作流定义未找到 ====================

    @Test
    void 工作流定义未找到_标记FAILED() throws Exception {
        var context = new WorkflowContext();
        var instance = 创建中断实例("inst-no-def", "wf-missing", WorkflowState.RUNNING, 0, context);

        when(repository.findInstancesByState(WorkflowState.RUNNING, WorkflowState.WAITING))
                .thenReturn(List.of(instance));
        when(registry.find("wf-missing")).thenReturn(Optional.empty());

        var latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            WorkflowInstance updated = invocation.getArgument(0);
            if (updated.state() == WorkflowState.FAILED) {
                latch.countDown();
            }
            return null;
        }).when(repository).updateInstance(any());

        engine.recoverInterruptedInstances();

        assertTrue(latch.await(5, TimeUnit.SECONDS), "崩溃恢复应在 5 秒内完成");

        verify(repository).updateInstance(argThat(inst ->
                inst.state() == WorkflowState.FAILED
                && "崩溃恢复失败：上下文数据损坏".equals(inst.failureReason())));
        verify(stepExecutor, never()).execute(any(), any(), any());
    }

    // ==================== Virtual Thread 非阻塞 ====================

    @Test
    void 恢复使用VirtualThread_不阻塞调用线程() throws Exception {
        // 模拟一个耗时的恢复过程
        var step1 = 创建NoopStep("step1");
        var def = 创建简单工作流("wf-vt", step1);
        var context = new WorkflowContext();
        var instance = 创建中断实例("inst-vt", "wf-vt", WorkflowState.RUNNING, 0, context);

        when(repository.findInstancesByState(WorkflowState.RUNNING, WorkflowState.WAITING))
                .thenReturn(List.of(instance));
        when(registry.find("wf-vt")).thenReturn(Optional.of(def));
        when(stepExecutor.execute(any(), any(), any())).thenAnswer(invocation -> {
            Thread.sleep(500); // 模拟耗时步骤
            return Map.of("ok", true);
        });

        long start = System.currentTimeMillis();
        engine.recoverInterruptedInstances();
        long elapsed = System.currentTimeMillis() - start;

        // recoverInterruptedInstances() 应立即返回（Virtual Thread 异步执行）
        assertTrue(elapsed < 200, "recoverInterruptedInstances() 应立即返回，实际耗时: " + elapsed + "ms");
    }

    // ==================== 无中断实例 ====================

    @Test
    void 无中断实例_正常完成() throws Exception {
        when(repository.findInstancesByState(WorkflowState.RUNNING, WorkflowState.WAITING))
                .thenReturn(List.of());

        engine.recoverInterruptedInstances();

        // 等待 Virtual Thread 完成
        Thread.sleep(200);

        verify(stepExecutor, never()).execute(any(), any(), any());
        verify(registry, never()).find(any());
    }
}
