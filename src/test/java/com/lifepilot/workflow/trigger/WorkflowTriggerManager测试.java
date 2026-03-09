package com.lifepilot.workflow.trigger;

import com.lifepilot.workflow.engine.WorkflowEngine;
import com.lifepilot.workflow.model.*;
import com.lifepilot.workflow.model.WorkflowStep.NoopStep;
import com.lifepilot.workflow.registry.WorkflowRegistry;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WorkflowTriggerManager 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
@SuppressWarnings("NullableProblems")
class WorkflowTriggerManager测试 {

    private WorkflowEngine engine;
    private WorkflowRegistry registry;
    private WorkflowRepository repository;
    private TaskScheduler taskScheduler;
    private WorkflowTriggerManager manager;
    private ScheduledFuture<?> mockFuture;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        engine = mock(WorkflowEngine.class);
        registry = mock(WorkflowRegistry.class);
        repository = mock(WorkflowRepository.class);
        taskScheduler = mock(TaskScheduler.class);
        mockFuture = mock(ScheduledFuture.class);
        manager = new WorkflowTriggerManager(engine, registry, repository, taskScheduler);
    }

    // ==================== 辅助方法 ====================

    private WorkflowDefinition 创建工作流(String id, WorkflowTrigger... triggers) {
        return WorkflowDefinition.builder()
                .id(id)
                .name("测试工作流-" + id)
                .enabled(true)
                .triggers(List.of(triggers))
                .steps(List.of(new NoopStep("s1", "步骤1", List.of(), null)))
                .build();
    }

    private WorkflowInstance 创建运行中实例(String workflowId) {
        return WorkflowInstance.builder()
                .id("inst-" + workflowId)
                .workflowId(workflowId)
                .state(WorkflowState.RUNNING)
                .completedStepIds(Set.of())
                .pendingApprovalStepId(null)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    /** 配置 TaskScheduler mock 返回 mockFuture。 */
    private void 配置TaskScheduler返回MockFuture() {
        doReturn(mockFuture).when(taskScheduler)
                .schedule(any(Runnable.class), any(CronTrigger.class));
    }

    /** 捕获注册到 TaskScheduler 的 Runnable 并返回。 */
    private Runnable 捕获CronRunnable() {
        var captor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(captor.capture(), any(CronTrigger.class));
        return captor.getValue();
    }

    // ==================== registerAllTriggers() 测试 ====================

    @Nested
    class registerAllTriggers方法 {

        @Test
        void 注册已启用工作流的Cron和Event触发器() {
            var def = 创建工作流("wf-1",
                    new WorkflowTrigger.CronTrigger("0 0 * * * *"),
                    new WorkflowTrigger.EventTrigger("TaskCompleted"));
            when(registry.listEnabled()).thenReturn(List.of(def));
            配置TaskScheduler返回MockFuture();

            manager.registerAllTriggers();

            // Cron 调度注册了一次
            verify(taskScheduler).schedule(any(Runnable.class), any(CronTrigger.class));
        }

        @Test
        void ManualTrigger不注册调度() {
            var def = 创建工作流("wf-manual", new WorkflowTrigger.ManualTrigger());
            when(registry.listEnabled()).thenReturn(List.of(def));

            manager.registerAllTriggers();

            verifyNoInteractions(taskScheduler);
        }
    }

    // ==================== fireCron() 测试 ====================

    @Nested
    class fireCron方法 {

        @Test
        void 存在RUNNING实例时跳过执行() {
            var def = 创建工作流("wf-cron", new WorkflowTrigger.CronTrigger("0 0 * * * *"));
            when(registry.listEnabled()).thenReturn(List.of(def));
            配置TaskScheduler返回MockFuture();
            manager.registerAllTriggers();

            // 模拟存在 RUNNING 实例
            when(repository.findInstancesByState(WorkflowState.RUNNING))
                    .thenReturn(List.of(创建运行中实例("wf-cron")));

            // 手动触发 fireCron
            捕获CronRunnable().run();

            verify(engine, never()).execute(anyString(), anyMap());
        }

        @Test
        void 无RUNNING实例时正常执行() {
            var def = 创建工作流("wf-cron2", new WorkflowTrigger.CronTrigger("0 0 * * * *"));
            when(registry.listEnabled()).thenReturn(List.of(def));
            配置TaskScheduler返回MockFuture();
            manager.registerAllTriggers();

            when(repository.findInstancesByState(WorkflowState.RUNNING))
                    .thenReturn(List.of());

            捕获CronRunnable().run();

            verify(engine).execute(eq("wf-cron2"), eq(Map.of()));
        }
    }

    // ==================== onApplicationEvent() 测试 ====================

    @Nested
    class onApplicationEvent方法 {

        @Test
        void 事件类型匹配时触发对应工作流() {
            var def = 创建工作流("wf-event", new WorkflowTrigger.EventTrigger("CustomTestEvent"));
            when(registry.listEnabled()).thenReturn(List.of(def));
            manager.registerAllTriggers();

            manager.onApplicationEvent(new CustomTestEvent(this));

            verify(engine).execute(eq("wf-event"), eq(Map.of("eventType", "CustomTestEvent")));
        }

        @Test
        void 事件类型不匹配时不触发() {
            var def = 创建工作流("wf-event2", new WorkflowTrigger.EventTrigger("SomeOtherEvent"));
            when(registry.listEnabled()).thenReturn(List.of(def));
            manager.registerAllTriggers();

            manager.onApplicationEvent(new CustomTestEvent(this));

            verify(engine, never()).execute(anyString(), anyMap());
        }

        /** 测试用自定义事件。 */
        static class CustomTestEvent extends ApplicationEvent {
            public CustomTestEvent(Object source) {
                super(source);
            }
        }
    }

    // ==================== unregisterTriggers() 测试 ====================

    @Nested
    class unregisterTriggers方法 {

        @Test
        void 取消Cron调度并移除事件绑定() {
            var def = 创建工作流("wf-unreg",
                    new WorkflowTrigger.CronTrigger("0 0 * * * *"),
                    new WorkflowTrigger.EventTrigger("UnregTestEvent"));
            when(registry.listEnabled()).thenReturn(List.of(def));
            配置TaskScheduler返回MockFuture();
            manager.registerAllTriggers();

            manager.unregisterTriggers("wf-unreg");

            // Cron ScheduledFuture 被取消
            verify(mockFuture).cancel(false);

            // 事件触发不再生效
            manager.onApplicationEvent(new UnregTestEvent(this));
            verify(engine, never()).execute(eq("wf-unreg"), anyMap());
        }

        /** 测试用自定义事件。 */
        static class UnregTestEvent extends ApplicationEvent {
            public UnregTestEvent(Object source) {
                super(source);
            }
        }
    }

    // ==================== 无效 Cron 表达式 ====================

    @Nested
    class 无效Cron表达式 {

        @Test
        void 无效Cron表达式不抛异常_优雅处理() {
            // CronTrigger 构造函数在表达式无效时抛出 IllegalArgumentException
            // WorkflowTriggerManager.registerCron() 捕获该异常并记录 WARN 日志
            var def = 创建工作流("wf-bad-cron",
                    new WorkflowTrigger.CronTrigger("invalid-cron-expression"));
            when(registry.listEnabled()).thenReturn(List.of(def));

            // 不应抛出异常
            assertDoesNotThrow(() -> manager.registerAllTriggers());

            // TaskScheduler.schedule() 不应被调用（异常在 CronTrigger 构造时已抛出）
            verify(taskScheduler, never()).schedule(any(Runnable.class), any(CronTrigger.class));
        }
    }
}
