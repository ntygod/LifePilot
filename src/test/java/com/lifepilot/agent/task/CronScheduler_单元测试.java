package com.lifepilot.agent.task;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CronScheduler 单元测试。
 *
 * <p>验证 schedule/cancel 逻辑、isSilentResponse 协议判断、executeTask 流程。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
class CronScheduler_单元测试 {

    private CronScheduler cronScheduler;
    private CronTaskRepository repository;
    private AgentOrchestrator agentOrchestrator;
    private NotificationService notificationService;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("PRAGMA foreign_keys = ON");
        jdbc.execute("""
                CREATE TABLE cron_tasks (
                    id TEXT PRIMARY KEY, name TEXT NOT NULL, schedule TEXT NOT NULL,
                    instruction TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'active',
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    skill_ids TEXT
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

        var config = new AgentConfigProperties();
        var notificationProperties = new com.lifepilot.notification.config.NotificationProperties();
        cronScheduler = new CronScheduler(scheduler, repository, agentOrchestrator, notificationService, notificationProperties);
    }

    // ---- isSilentResponse 协议判断 ----

    @Test
    void isSilentResponse_null内容_返回true() {
        assertThat(CronScheduler.isSilentResponse(null, "TASK_SILENT")).isTrue();
    }

    @Test
    void isSilentResponse_空白内容_返回true() {
        assertThat(CronScheduler.isSilentResponse("  ", "TASK_SILENT")).isTrue();
    }

    @Test
    void isSilentResponse_开头匹配_返回true() {
        assertThat(CronScheduler.isSilentResponse("TASK_SILENT\n无需汇报", "TASK_SILENT")).isTrue();
    }

    @Test
    void isSilentResponse_结尾匹配_返回true() {
        assertThat(CronScheduler.isSilentResponse("检查完毕，一切正常\nTASK_SILENT", "TASK_SILENT")).isTrue();
    }

    @Test
    void isSilentResponse_中间出现_返回false() {
        assertThat(CronScheduler.isSilentResponse("结果是 TASK_SILENT 但还有内容", "TASK_SILENT")).isFalse();
    }

    @Test
    void isSilentResponse_不包含token_返回false() {
        assertThat(CronScheduler.isSilentResponse("这是正常回复内容", "TASK_SILENT")).isFalse();
    }

    // ---- schedule / cancel ----

    @Test
    void schedule_paused任务_不注册定时器() {
        var now = Instant.now().toString();
        var task = new CronTaskEntry("t1", "测试", "0 0 6 * * *", "指令", "paused", now, now);
        cronScheduler.schedule(task);
        // paused 任务不应注册，cancel 不会有效果
        cronScheduler.cancel("t1");
    }

    @Test
    void cancel_不存在的任务_不抛异常() {
        cronScheduler.cancel("not-exist");
    }

    // ---- executeTask ----

    @Test
    void executeTask_正常执行_写入成功日志() {
        var now = Instant.now().toString();
        var task = new CronTaskEntry("t-exec", "日报", "0 0 6 * * *", "生成日报", "active", now, now);
        repository.save(task);

        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenReturn(new AgentResponse("trace1", "cron:t-exec", "日报内容", 100, 3, null));
        when(notificationService.send(any())).thenReturn(List.of("n1"));

        cronScheduler.executeTask(task);

        // 验证通知被发送
        verify(notificationService, times(1)).send(any());
    }

    @Test
    void executeTask_静默回复_不发送通知() {
        var now = Instant.now().toString();
        var task = new CronTaskEntry("t-silent", "检查", "0 0 6 * * *", "检查状态", "active", now, now);
        repository.save(task);

        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenReturn(new AgentResponse("trace2", "cron:t-silent", "TASK_SILENT", 50, 1, null));

        cronScheduler.executeTask(task);

        // 静默回复不应发送通知
        verify(notificationService, never()).send(any());
    }

    @Test
    void executeTask_异常_写入失败日志() {
        var now = Instant.now().toString();
        var task = new CronTaskEntry("t-err", "出错", "0 0 6 * * *", "指令", "active", now, now);
        repository.save(task);

        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenThrow(new RuntimeException("LLM 不可用"));

        cronScheduler.executeTask(task);

        // 不应抛异常，日志应已写入
        verify(notificationService, never()).send(any());
    }

    // ---- restoreAll ----

    @Test
    void restoreAll_恢复active任务() {
        var now = Instant.now().toString();
        repository.save(new CronTaskEntry("r1", "任务1", "0 0 6 * * *", "指令1", "active", now, now));
        repository.save(new CronTaskEntry("r2", "任务2", "0 0 8 * * *", "指令2", "active", now, now));
        repository.save(new CronTaskEntry("r3", "任务3", "0 0 10 * * *", "指令3", "paused", now, now));

        cronScheduler.restoreAll();

        // paused 任务不应被注册，cancel 两个 active 任务
        cronScheduler.cancel("r1");
        cronScheduler.cancel("r2");
    }
}
