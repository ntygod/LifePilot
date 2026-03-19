package com.lifepilot.agent.task;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.Urgency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 心跳巡检运行器。
 *
 * <p>定时给 Agent 发一条消息，Agent 自己决定做什么。
 * 设计极其简单：读取 HEARTBEAT.md → 构造 prompt → 调用 AgentOrchestrator。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class HeartbeatRunner {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatRunner.class);

    private final ScheduledExecutorService scheduler;
    private final AgentOrchestrator agentOrchestrator;
    private final NotificationService notificationService;
    private final AgentConfigProperties config;

    public HeartbeatRunner(ScheduledExecutorService scheduler,
                           AgentOrchestrator agentOrchestrator,
                           NotificationService notificationService,
                           AgentConfigProperties config) {
        this.scheduler = scheduler;
        this.agentOrchestrator = agentOrchestrator;
        this.notificationService = notificationService;
        this.config = config;
    }

    /**
     * 启动心跳。在 ApplicationReadyEvent 时调用。
     */
    public void start() {
        long intervalMs = config.getTask().getHeartbeatIntervalSeconds() * 1000L;
        scheduler.scheduleAtFixedRate(this::beat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("心跳巡检已启动: interval={}s, file={}",
                config.getTask().getHeartbeatIntervalSeconds(),
                config.getTask().getHeartbeatFile());
    }

    /**
     * 单次心跳。
     */
    void beat() {
        // 活跃时段检查
        if (!isWithinActiveHours()) {
            log.debug("心跳跳过: 当前不在活跃时段");
            return;
        }

        // 读取 HEARTBEAT.md
        String checklist = readHeartbeatFile();
        if (checklist == null || checklist.isBlank()) {
            log.debug("心跳跳过: HEARTBEAT.md 为空或不存在");
            return;
        }

        // 构造心跳 prompt
        String prompt = """
                [心跳巡检]
                以下是你的 checklist：
                
                %s
                
                按 checklist 检查。不要推测或重复之前已汇报的内容。无事则回复 HEARTBEAT_OK。""".formatted(checklist);
        var request = new AgentRequest(prompt, "heartbeat:main", "heartbeat");

        try {
            var response = agentOrchestrator.run(request);

            // HEARTBEAT_OK 协议
            boolean ok = CronScheduler.isSilentResponse(response.content(), "HEARTBEAT_OK");

            if (!ok && response.content() != null && !response.content().isBlank()
                    && response.terminationReason() == null) {
                notificationService.send(new NotificationRequest(
                        "default",
                        new ResponseContent.TextContent("【心跳巡检】\n" + response.content()),
                        Urgency.LOW, null, "heartbeat", Map.of()
                ));
            }
        } catch (Exception e) {
            log.warn("心跳执行异常: {}", e.getMessage());
        }
    }

    /**
     * 判断当前是否在活跃时段内。支持跨午夜配置。
     *
     * @return 是否在活跃时段
     */
    boolean isWithinActiveHours() {
        var task = config.getTask();
        if (task.getActiveHoursStart() == null || task.getActiveHoursEnd() == null) {
            return true; // 未配置则全天活跃
        }
        LocalTime now = LocalTime.now();
        LocalTime start = LocalTime.parse(task.getActiveHoursStart());
        LocalTime end = LocalTime.parse(task.getActiveHoursEnd());

        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        } else {
            // 跨午夜：如 22:00 - 08:00
            return !now.isBefore(start) || now.isBefore(end);
        }
    }

    /**
     * 读取心跳 checklist 文件。
     *
     * @return 文件内容，文件不存在或读取失败返回 null
     */
    String readHeartbeatFile() {
        String filePath = config.getTask().getHeartbeatFile();
        if (filePath == null || filePath.isBlank()) return null;

        // 解析 ~ 为用户目录
        if (filePath.startsWith("~")) {
            filePath = System.getProperty("user.home") + filePath.substring(1);
        }

        Path path = Path.of(filePath);
        if (!Files.exists(path)) return null;

        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.warn("读取 HEARTBEAT.md 失败: path={}, error={}", filePath, e.getMessage());
            return null;
        }
    }
}
