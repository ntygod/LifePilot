package com.lifepilot.workflow.engine;

import com.lifepilot.workflow.config.WorkflowConfigProperties;
import com.lifepilot.workflow.engine.StepExecutor.WorkflowStepException;
import com.lifepilot.workflow.expression.ExpressionEngine;
import com.lifepilot.workflow.model.*;
import com.lifepilot.workflow.model.WorkflowStep.*;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * WorkflowEngine 单元测试。
 *
 * <p>测试通过 {@link WorkflowEngine#executeFromInstance(String)} 驱动 DAG 执行，
 * 使用 {@link ArgumentCaptor} 捕获 {@code repository.updateInstance()} 的最终状态。
 *
 * @author zsg
 * @since 2026-02-26
 */
class WorkflowEngineTest {

    private WorkflowRegistry registry;
    private StepExecutor stepExecutor;
    private ExpressionEngine expressionEngine;
    private WorkflowRepository repository;
    private WorkflowConfigProperties config;
    private DagScheduler dagScheduler;
    private WorkflowEventRecorder eventRecorder;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        registry = mock(WorkflowRegistry.class);
        stepExecutor = mock(StepExecutor.class);
        expressionEngine = new ExpressionEngine();
        repository = mock(WorkflowRepository.class);
        config = new WorkflowConfigProperties();
        dagScheduler = new DagScheduler();
        eventRecorder = mock(WorkflowEventRecorder.class);
        engine = new WorkflowEngine(registry, stepExecutor, expressionEngine,
                repository, config, dagScheduler, eventRecorder);
    }

    private WorkflowDefinition createSimpleDef(String id, WorkflowStep... steps) {
        return WorkflowDefinition.builder()
                .id(id)
                .name("test-" + id)
                .enabled(true)
                .steps(List.of(steps))
                .build();
    }

    private NoopStep noop(String id) {
        return new NoopStep(id, "Noop-" + id, List.of(), null);
    }

    /**
     * 创建 CREATED 状态的实例并 mock repository.findInstance() 返回它。
     */
    private WorkflowInstance createAndMockInstance(String instanceId, String workflowId,
                                                    Map<String, Object> inputs) {
        Instant now = Instant.now();
        WorkflowContext context = new WorkflowContext();
        if (inputs != null && !inputs.isEmpty()) {
            context.set("inputs", inputs);
        }
        WorkflowInstance instance = WorkflowInstance.builder()
                .id(instanceId)
                .workflowId(workflowId)
                .state(WorkflowState.CREATED)
                .context(context)
                .completedStepIds(Set.of())
                .pendingApprovalStepId(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
        when(repository.findInstance(instanceId)).thenReturn(Optional.of(instance));
        return instance;
    }

    /**
     * 从 repository.updateInstance() 捕获列表中获取最后一次更新的实例。
     */
    private WorkflowInstance captureLastUpdatedInstance() {
        ArgumentCaptor<WorkflowInstance> captor = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(repository, atLeastOnce()).updateInstance(captor.capture());
        List<WorkflowInstance> allValues = captor.getAllValues();
        return allValues.getLast();
    }

    @Nested
    class executeFromInstance方法 {

        @Test
        void 多步骤工作流_全部成功_状态为COMPLETED() {
            var step1 = noop("step1");
            var step2 = noop("step2");
            var step3 = noop("step3");
            var def = createSimpleDef("wf-1", step1, step2, step3);

            createAndMockInstance("inst-1", "wf-1", Map.of("key", "value"));
            when(registry.find("wf-1")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(any(), any(), any())).thenReturn(Map.of("ok", true));

            engine.executeFromInstance("inst-1");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.COMPLETED, result.state());
            assertEquals("wf-1", result.workflowId());
            assertNotNull(result.completedAt());

            verify(repository, atLeast(3)).updateInstance(any());
            verify(repository, times(3)).insertStepLog(any());
        }

        @Test
        void 状态转换_CREATED到RUNNING到COMPLETED() {
            var step = noop("step1");
            var def = createSimpleDef("wf-2", step);

            createAndMockInstance("inst-2", "wf-2", Map.of());
            when(registry.find("wf-2")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(any(), any(), any())).thenReturn(Map.of());

            engine.executeFromInstance("inst-2");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.COMPLETED, result.state());
        }

        @Test
        void 工作流定义未找到_异常被内部捕获() {
            createAndMockInstance("inst-3", "not-exist", Map.of());
            when(registry.find("not-exist")).thenReturn(Optional.empty());

            // executeFromInstance 内部捕获异常并尝试 failWorkflow，
            // 但 CREATED→FAILED 不是合法状态转换，所以不会调用 updateInstance
            engine.executeFromInstance("inst-3");

            verify(repository, never()).updateInstance(any());
        }

        @Test
        void 实例未找到_抛出异常() {
            when(repository.findInstance("not-exist")).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> engine.executeFromInstance("not-exist"));
        }

        @Test
        void 步骤输出存储到Context() {
            var step = noop("fetch");
            var def = createSimpleDef("wf-ctx", step);

            createAndMockInstance("inst-ctx", "wf-ctx", Map.of());
            when(registry.find("wf-ctx")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(any(), any(), any()))
                    .thenReturn(Map.of("data", "hello"));

            engine.executeFromInstance("inst-ctx");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.COMPLETED, result.state());
            Optional<Object> output = result.context().get("steps.fetch.output");
            assertTrue(output.isPresent());
        }
    }

    @Nested
    class 错误策略 {

        @Test
        void Retry策略_重试耗尽后回退到Fail() {
            var step = new NoopStep("retry-step", "retry", List.of(),
                    new ErrorStrategy.Retry(3, 10, 50));
            var def = createSimpleDef("wf-retry", step);

            createAndMockInstance("inst-retry", "wf-retry", Map.of());
            when(registry.find("wf-retry")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(any(), any(), any()))
                    .thenThrow(new WorkflowStepException("retry-step", "fail"));

            engine.executeFromInstance("inst-retry");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.FAILED, result.state());
            assertNotNull(result.failureReason());
            assertTrue(result.failureReason().contains("重试耗尽"));
            verify(repository, times(3)).insertStepLog(any());
        }

        @Test
        void Retry策略_第二次成功() {
            var step = new NoopStep("retry-ok", "retry-ok", List.of(),
                    new ErrorStrategy.Retry(3, 10, 50));
            var def = createSimpleDef("wf-retry-ok", step);

            createAndMockInstance("inst-retry-ok", "wf-retry-ok", Map.of());
            when(registry.find("wf-retry-ok")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(any(), any(), any()))
                    .thenThrow(new WorkflowStepException("retry-ok", "first fail"))
                    .thenReturn(Map.of("ok", true));

            engine.executeFromInstance("inst-retry-ok");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.COMPLETED, result.state());
        }

        @Test
        void Skip策略_跳过失败步骤继续执行() {
            var step1 = new NoopStep("skip-step", "skip", List.of(),
                    new ErrorStrategy.Skip("optional"));
            var step2 = noop("next-step");
            var def = createSimpleDef("wf-skip", step1, step2);

            createAndMockInstance("inst-skip", "wf-skip", Map.of());
            when(registry.find("wf-skip")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(eq(step1), any(), any()))
                    .thenThrow(new WorkflowStepException("skip-step", "fail"));
            when(stepExecutor.execute(eq(step2), any(), any()))
                    .thenReturn(Map.of("ok", true));

            engine.executeFromInstance("inst-skip");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.COMPLETED, result.state());
        }

        @Test
        void Fail策略_步骤失败终止工作流() {
            var step = new NoopStep("fail-step", "fail", List.of(),
                    new ErrorStrategy.Fail());
            var def = createSimpleDef("wf-fail", step);

            createAndMockInstance("inst-fail", "wf-fail", Map.of());
            when(registry.find("wf-fail")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(any(), any(), any()))
                    .thenThrow(new WorkflowStepException("fail-step", "fail"));

            engine.executeFromInstance("inst-fail");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.FAILED, result.state());
            assertNotNull(result.failureReason());
        }

        @Test
        void Compensate策略_执行补偿步骤() {
            var compStep = noop("comp-step");
            var step = new NoopStep("main-step", "main", List.of(),
                    new ErrorStrategy.Compensate(compStep));
            var def = createSimpleDef("wf-comp", step);

            createAndMockInstance("inst-comp", "wf-comp", Map.of());
            when(registry.find("wf-comp")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(eq(step), any(), any()))
                    .thenThrow(new WorkflowStepException("main-step", "fail"));
            when(stepExecutor.execute(eq(compStep), any(), any()))
                    .thenReturn(Map.of("compensated", true));

            engine.executeFromInstance("inst-comp");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.FAILED, result.state());
            verify(stepExecutor).execute(eq(compStep), any(), any());
        }

        @Test
        void 无ErrorStrategy时默认使用Fail() {
            var step = noop("no-strategy");
            var def = createSimpleDef("wf-default", step);

            createAndMockInstance("inst-default", "wf-default", Map.of());
            when(registry.find("wf-default")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(any(), any(), any()))
                    .thenThrow(new WorkflowStepException("no-strategy", "fail"));

            engine.executeFromInstance("inst-default");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.FAILED, result.state());
        }
    }

    @Nested
    class WaitStep处理 {

        @Test
        void WaitStep_转换为WAITING状态() {
            var waitStep = new WaitStep("wait-1", "wait", 60, List.of(), null);
            var def = createSimpleDef("wf-wait", waitStep);

            createAndMockInstance("inst-wait", "wf-wait", Map.of());
            when(registry.find("wf-wait")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(eq(waitStep), any(), any()))
                    .thenReturn(Map.of("__type", "wait", "durationSeconds", 60L));

            engine.executeFromInstance("inst-wait");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.WAITING, result.state());
        }
    }

    @Nested
    class resume方法 {

        @Test
        void 从WAITING状态恢复执行() {
            var step1 = new WaitStep("wait-1", "wait", 60, List.of(), null);
            var step2 = noop("after-wait");
            var def = createSimpleDef("wf-resume", step1, step2);

            WorkflowInstance waitingInstance = WorkflowInstance.builder()
                    .id("inst-1")
                    .workflowId("wf-resume")
                    .state(WorkflowState.WAITING)
                    .context(new WorkflowContext())
                    .completedStepIds(Set.of())
                    .pendingApprovalStepId(null)
                    .startedAt(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(repository.findInstance("inst-1")).thenReturn(Optional.of(waitingInstance));
            when(registry.find("wf-resume")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(eq(step2), any(), any()))
                    .thenReturn(Map.of("ok", true));

            WorkflowInstance result = engine.resume("inst-1");

            assertEquals(WorkflowState.COMPLETED, result.state());
        }

        @Test
        void 实例未找到_抛出异常() {
            when(repository.findInstance("not-exist")).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> engine.resume("not-exist"));
        }
    }

    @Nested
    class cancel方法 {

        @Test
        void 取消RUNNING实例_状态变为CANCELLED() {
            WorkflowInstance runningInstance = WorkflowInstance.builder()
                    .id("inst-cancel")
                    .workflowId("wf-1")
                    .state(WorkflowState.RUNNING)
                    .context(new WorkflowContext())
                    .completedStepIds(Set.of())
                    .pendingApprovalStepId(null)
                    .startedAt(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(repository.findInstance("inst-cancel")).thenReturn(Optional.of(runningInstance));

            WorkflowInstance result = engine.cancel("inst-cancel");

            assertEquals(WorkflowState.CANCELLED, result.state());
            assertNotNull(result.completedAt());
            verify(repository).updateInstance(any());
        }

        @Test
        void 实例未找到_抛出异常() {
            when(repository.findInstance("not-exist")).thenReturn(Optional.empty());

            assertThrows(IllegalArgumentException.class,
                    () -> engine.cancel("not-exist"));
        }
    }

    @Nested
    class 无效状态转换 {

        @Test
        void COMPLETED状态不能转换为RUNNING() {
            WorkflowInstance completedInstance = WorkflowInstance.builder()
                    .id("inst-completed")
                    .workflowId("wf-1")
                    .state(WorkflowState.COMPLETED)
                    .context(new WorkflowContext())
                    .completedStepIds(Set.of())
                    .pendingApprovalStepId(null)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(repository.findInstance("inst-completed")).thenReturn(Optional.of(completedInstance));

            WorkflowInstance result = engine.resume("inst-completed");

            assertEquals(WorkflowState.COMPLETED, result.state());
        }

        @Test
        void FAILED状态不能取消() {
            WorkflowInstance failedInstance = WorkflowInstance.builder()
                    .id("inst-failed")
                    .workflowId("wf-1")
                    .state(WorkflowState.FAILED)
                    .context(new WorkflowContext())
                    .completedStepIds(Set.of())
                    .pendingApprovalStepId(null)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            when(repository.findInstance("inst-failed")).thenReturn(Optional.of(failedInstance));

            WorkflowInstance result = engine.cancel("inst-failed");

            assertEquals(WorkflowState.FAILED, result.state());
        }
    }

    @Nested
    class SubWorkflowStep处理 {

        @Test
        void 子工作流执行成功() {
            var subStep = new SubWorkflowStep("sub-1", "sub",
                    "child-wf", Map.of("k", "v"), List.of(), null);
            var mainDef = createSimpleDef("main-wf", subStep);

            var childStep = noop("child-step");
            var childDef = createSimpleDef("child-wf", childStep);

            createAndMockInstance("inst-main", "main-wf", Map.of());
            when(registry.find("main-wf")).thenReturn(Optional.of(mainDef));
            when(registry.find("child-wf")).thenReturn(Optional.of(childDef));

            when(stepExecutor.execute(eq(subStep), any(), any()))
                    .thenReturn(Map.of(
                            "__type", "sub-workflow",
                            "workflowId", "child-wf",
                            "params", Map.of()));

            when(stepExecutor.execute(eq(childStep), any(), any()))
                    .thenReturn(Map.of("ok", true));

            engine.executeFromInstance("inst-main");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.COMPLETED, result.state());
        }

        @Test
        void 子工作流嵌套深度超限_标记FAILED() {
            config.setMaxNestingDepth(1);

            var subStep = new SubWorkflowStep("sub-1", "sub",
                    "child-wf", Map.of("k", "v"), List.of(), null);
            var mainDef = createSimpleDef("main-wf", subStep);

            var grandSubStep = new SubWorkflowStep("grand-sub", "grand",
                    "grandchild-wf", Map.of("k", "v"), List.of(), null);
            var childDef = createSimpleDef("child-wf", grandSubStep);

            createAndMockInstance("inst-nest", "main-wf", Map.of());
            when(registry.find("main-wf")).thenReturn(Optional.of(mainDef));
            when(registry.find("child-wf")).thenReturn(Optional.of(childDef));

            when(stepExecutor.execute(eq(subStep), any(), any()))
                    .thenReturn(Map.of(
                            "__type", "sub-workflow",
                            "workflowId", "child-wf",
                            "params", Map.of()));
            when(stepExecutor.execute(eq(grandSubStep), any(), any()))
                    .thenReturn(Map.of(
                            "__type", "sub-workflow",
                            "workflowId", "grandchild-wf",
                            "params", Map.of()));

            engine.executeFromInstance("inst-nest");

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.FAILED, result.state());
        }
    }

    @Nested
    class StepLog记录 {

        @Test
        void 每次步骤执行插入StepLog() {
            var step1 = noop("s1");
            var step2 = noop("s2");
            var def = createSimpleDef("wf-log", step1, step2);

            createAndMockInstance("inst-log", "wf-log", Map.of());
            when(registry.find("wf-log")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(any(), any(), any())).thenReturn(Map.of());

            engine.executeFromInstance("inst-log");

            verify(repository, times(2)).insertStepLog(argThat(log ->
                    log.state() == StepState.COMPLETED && log.attempt() == 1));
        }

        @Test
        void 失败步骤也记录StepLog() {
            var step = new NoopStep("fail-s", "fail", List.of(),
                    new ErrorStrategy.Fail());
            var def = createSimpleDef("wf-fail-log", step);

            createAndMockInstance("inst-fail-log", "wf-fail-log", Map.of());
            when(registry.find("wf-fail-log")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(any(), any(), any()))
                    .thenThrow(new WorkflowStepException("fail-s", "fail"));

            engine.executeFromInstance("inst-fail-log");

            verify(repository).insertStepLog(argThat(log ->
                    log.state() == StepState.FAILED));
        }
    }

    @Nested
    class 并发执行 {

        @Test
        void 并发分支一条失败时快速返回并取消其他分支() throws Exception {
            var root = noop("root");
            var fastFail = new NoopStep("fast-fail", "fast-fail", List.of("root"), null);
            var slowStep = new NoopStep("slow-step", "slow-step", List.of("root"), null);
            var def = createSimpleDef("wf-parallel-fail-fast", root, fastFail, slowStep);

            createAndMockInstance("inst-parallel-fail-fast", "wf-parallel-fail-fast", Map.of());
            when(registry.find("wf-parallel-fail-fast")).thenReturn(Optional.of(def));

            CountDownLatch slowStarted = new CountDownLatch(1);

            when(stepExecutor.execute(eq(root), any(), any())).thenReturn(Map.of("ok", true));

            when(stepExecutor.execute(eq(slowStep), any(), any())).thenAnswer(invocation -> {
                slowStarted.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new WorkflowStepException("slow-step", "slow interrupted", e);
                }
                return Map.of("ok", true);
            });
            when(stepExecutor.execute(eq(fastFail), any(), any())).thenAnswer(invocation -> {
                assertTrue(slowStarted.await(1, TimeUnit.SECONDS));
                throw new WorkflowStepException("fast-fail", "boom");
            });

            ExecutorService executorService = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = executorService.submit(() -> engine.executeFromInstance("inst-parallel-fail-fast"));
                future.get(2, TimeUnit.SECONDS);
            } finally {
                executorService.shutdownNow();
            }

            WorkflowInstance result = captureLastUpdatedInstance();
            assertEquals(WorkflowState.FAILED, result.state());
            assertTrue(result.failureReason().contains("fast-fail"));
            verify(repository, never()).insertStepLog(argThat(log ->
                    "slow-step".equals(log.stepId()) && log.state() == StepState.FAILED));
        }
    }

    @Nested
    class 步骤类型提取 {

        @Test
        void NoopStep类型为noop() {
            var step = noop("s1");
            var def = createSimpleDef("wf-type", step);

            createAndMockInstance("inst-type", "wf-type", Map.of());
            when(registry.find("wf-type")).thenReturn(Optional.of(def));
            when(stepExecutor.execute(any(), any(), any())).thenReturn(Map.of());

            engine.executeFromInstance("inst-type");

            verify(repository).insertStepLog(argThat(log ->
                    "noop".equals(log.stepType())));
        }
    }
}
