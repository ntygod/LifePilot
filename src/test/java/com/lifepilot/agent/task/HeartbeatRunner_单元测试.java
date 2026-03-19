package com.lifepilot.agent.task;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * HeartbeatRunner 单元测试。
 *
 * <p>验证 beat 逻辑、活跃时段判断、空文件跳过、HEARTBEAT_OK 协议。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
class HeartbeatRunner_单元测试 {

    private HeartbeatRunner heartbeatRunner;
    private AgentOrchestrator agentOrchestrator;
    private NotificationService notificationService;
    private AgentConfigProperties config;
    private ScheduledExecutorService scheduler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        agentOrchestrator = mock(AgentOrchestrator.class);
        notificationService = mock(NotificationService.class);
        scheduler = Executors.newScheduledThreadPool(1);
        config = new AgentConfigProperties();
    }

    // ---- 活跃时段判断 ----

    @Test
    void isWithinActiveHours_未配置_全天活跃() {
        config.getTask().setActiveHoursStart(null);
        config.getTask().setActiveHoursEnd(null);
        heartbeatRunner = new HeartbeatRunner(scheduler, agentOrchestrator, notificationService, config);

        assertThat(heartbeatRunner.isWithinActiveHours()).isTrue();
    }

    // ---- readHeartbeatFile ----

    @Test
    void readHeartbeatFile_文件不存在_返回null() {
        config.getTask().setHeartbeatFile(tempDir.resolve("not-exist.md").toString());
        heartbeatRunner = new HeartbeatRunner(scheduler, agentOrchestrator, notificationService, config);

        assertThat(heartbeatRunner.readHeartbeatFile()).isNull();
    }

    @Test
    void readHeartbeatFile_文件存在_返回内容() throws IOException {
        Path file = tempDir.resolve("HEARTBEAT.md");
        Files.writeString(file, "# Checklist\n- 检查邮箱");
        config.getTask().setHeartbeatFile(file.toString());
        heartbeatRunner = new HeartbeatRunner(scheduler, agentOrchestrator, notificationService, config);

        String content = heartbeatRunner.readHeartbeatFile();
        assertThat(content).contains("检查邮箱");
    }

    @Test
    void readHeartbeatFile_路径为空_返回null() {
        config.getTask().setHeartbeatFile("");
        heartbeatRunner = new HeartbeatRunner(scheduler, agentOrchestrator, notificationService, config);

        assertThat(heartbeatRunner.readHeartbeatFile()).isNull();
    }

    // ---- beat ----

    @Test
    void beat_文件为空_跳过不调用Agent() {
        config.getTask().setHeartbeatFile(tempDir.resolve("empty.md").toString());
        heartbeatRunner = new HeartbeatRunner(scheduler, agentOrchestrator, notificationService, config);

        heartbeatRunner.beat();

        verify(agentOrchestrator, never()).run(any());
    }

    @Test
    void beat_有内容_调用Agent并通知() throws IOException {
        Path file = tempDir.resolve("HEARTBEAT.md");
        Files.writeString(file, "- 检查邮箱\n- 看日历");
        config.getTask().setHeartbeatFile(file.toString());
        heartbeatRunner = new HeartbeatRunner(scheduler, agentOrchestrator, notificationService, config);

        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenReturn(new AgentResponse("trace", "heartbeat:main", "有新邮件需要处理", 80, 2, null));
        when(notificationService.send(any())).thenReturn(List.of("n1"));

        heartbeatRunner.beat();

        verify(agentOrchestrator, times(1)).run(any());
        verify(notificationService, times(1)).send(any());
    }

    @Test
    void beat_HEARTBEAT_OK回复_不发送通知() throws IOException {
        Path file = tempDir.resolve("HEARTBEAT.md");
        Files.writeString(file, "- 检查状态");
        config.getTask().setHeartbeatFile(file.toString());
        heartbeatRunner = new HeartbeatRunner(scheduler, agentOrchestrator, notificationService, config);

        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenReturn(new AgentResponse("trace", "heartbeat:main", "HEARTBEAT_OK", 30, 1, null));

        heartbeatRunner.beat();

        verify(agentOrchestrator, times(1)).run(any());
        verify(notificationService, never()).send(any());
    }

    @Test
    void beat_Agent异常_不抛出() throws IOException {
        Path file = tempDir.resolve("HEARTBEAT.md");
        Files.writeString(file, "- 检查");
        config.getTask().setHeartbeatFile(file.toString());
        heartbeatRunner = new HeartbeatRunner(scheduler, agentOrchestrator, notificationService, config);

        when(agentOrchestrator.run(any(AgentRequest.class)))
                .thenThrow(new RuntimeException("LLM 不可用"));

        heartbeatRunner.beat(); // 不应抛异常

        verify(notificationService, never()).send(any());
    }
}
