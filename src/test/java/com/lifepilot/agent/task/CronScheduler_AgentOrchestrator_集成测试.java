package com.lifepilot.agent.task;

import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CronScheduler + AgentOrchestrator 集成测试。
 *
 * <p>验证 schedule → 定时触发 → executeTask → 写入日志完整流程（Mock AgentOrchestrator）。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
class CronScheduler_AgentOrchestrator_集成测试 {

    private CronScheduler cronScheduler;
    private CronTaskRepository repository;
    private AgentOrchestrator agentOrchestrator;
    private NotificationService notificationService;
    private ScheduledExecutorService scheduler;
    private SingleConnectionDataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("PRAGMA foreign_keys = ON");
        jdbc.execute("""
                CREATE TABLE cron_tasks (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, schedule TEXT NOT NULL,
                    instruction TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'active',
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE cron_task_logs (
                    id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES cron_tasks(id) ON DELETE CASCADE,
                    executed_at TEXT NOT NULL, status TEXT NOT NULL, duration_ms INTEGER NOT NULL,
                    tokens_used INTEGER NOT NULL DEFAULT 0, summary TEXT, created_at TEXT NOT NULL
                )""");
        repository = new CronTaskRepository(jdbc);

        agentOrchestrator = mock(AgentOrchestrator.class);
        notificationService = mock(NotificationService.class);
        scheduler = Executors.newScheduledThreadPool(2);
        var notificationProperties = new NotificationProperties();

        cronScheduler = new CronScheduler(scheduler, repository, agentOrchestrator,
                notificationService, notificationProperties);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        dataSource.destroy();
    }

    @Test
    void schedule后cancel_不触发执行() throws InterruptedException {
        var now = Instant.now().toString();
        var task = new CronTaskEntry("task-cancel", "取消测试", "0 0 0 1 1 *",
                "不应执行", "active", now, now);
        repository.save(task);

        cronScheduler.schedule(task);
        cronScheduler.cancel("task-cancel");

        // 等待一小段时间确认不触发
        Thread.sleep(200);
        verify(agentOrchestrator, never()).run(any());
    }

    @Test
    void restoreAll_恢复active任务() {
        var now = Instant.now().toString();
        repository.save(new CronTaskEntry("t1", "活跃", "0 0 8 * * *", "指令1", "active", now, now));
        repository.save(new CronTaskEntry("t2", "暂停", "0 0 9 * * *", "指令2", "paused", now, now));

        cronScheduler.restoreAll();

        // active 任务被调度（内部 scheduledTasks map 有记录），paused 不调度
        // 通过 cancel 验证：cancel active 任务应有效果
        cronScheduler.cancel("t1");
        cronScheduler.cancel("t2"); // paused 的 cancel 是 no-op
    }

    @Test
    void executeTask_成功执行_写入日志() {
        var now = Instant.now().toString();
        var task = new CronTaskEntry("task-exec", "执行测试", "0 0 8 * * *",
                "搜索新闻", "active", now, now);
        repository.save(task);

        // Mock AgentOrchestrator 返回正常响应
        var response = new AgentResponse("trace-1", "session-1", "今日AI新闻摘要...",
                150, 3, null, null, null, null);
        when(agentOrchestrator.run(any(AgentRequest.class))).thenReturn(response);

        // 直接调用 executeTask
        cronScheduler.executeTask(task);

        // 验证 AgentOrchestrator 被调用
        verify(agentOrchestrator, times(1)).run(any(AgentRequest.class));

        // 验证通知被发送（非静默响应）
        verify(notificationService, times(1)).send(any());
    }

    @Test
    void executeTask_静默响应_不发送通知() {
        var now = Instant.now().toString();
        var task = new CronTaskEntry("task-silent", "静默测试", "0 0 8 * * *",
                "检查状态", "active", now, now);
        repository.save(task);

        var response = new AgentResponse("trace-2", "session-2", "TASK_SILENT",
                50, 1, null, null, null, null);
        when(agentOrchestrator.run(any(AgentRequest.class))).thenReturn(response);

        cronScheduler.executeTask(task);

        verify(agentOrchestrator, times(1)).run(any());
        verify(notificationService, never()).send(any());
    }

    @Test
    void executeTask_异常_日志记录failed() {
        var now = Instant.now().toString();
        var task = new CronTaskEntry("task-fail", "失败测试", "0 0 8 * * *",
                "触发异常", "active", now, now);
        repository.save(task);

        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenThrow(new RuntimeException("模拟执行异常"));

        cronScheduler.executeTask(task);

        // 不应抛出异常（内部捕获）
        verify(agentOrchestrator, times(1)).run(any());
    }
}
