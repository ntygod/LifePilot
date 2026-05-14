package com.lifepilot.agent.task.proactive.training;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主动训练调度器 — 按配置间隔（默认 7 天）触发 {@link ProactiveTrainingReplayService}
 * 重新编译 few-shot 样例库。
 *
 * <p>本地个人助手场景默认单用户；多用户场景暂仅处理 {@code defaultUserId}，
 * 避免无谓遍历。将来上云 / 多账号时再扩展。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class ProactiveTrainingScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProactiveTrainingScheduler.class);

    /** 单次查询 ReminderExecutionRepository 的样本上限。 */
    private static final int SAMPLE_QUERY_LIMIT = 400;

    /** 启动初始延迟（秒）— 避免启动峰值堆叠。 */
    private static final long INITIAL_DELAY_SECONDS = 60;

    private final ScheduledExecutorService scheduler;
    private final ProactiveTrainingReplayService replayService;
    private final ProactiveFewShotLibrary library;
    private final AgentConfigProperties.TaskConfig taskConfig;
    @Nullable private final NotificationProperties notificationProperties;

    public ProactiveTrainingScheduler(ScheduledExecutorService scheduler,
                                      ProactiveTrainingReplayService replayService,
                                      ProactiveFewShotLibrary library,
                                      AgentConfigProperties.TaskConfig taskConfig,
                                      @Nullable NotificationProperties notificationProperties) {
        this.scheduler = scheduler;
        this.replayService = replayService;
        this.library = library;
        this.taskConfig = taskConfig;
        this.notificationProperties = notificationProperties;
    }

    /** 启动周期调度 — 受 {@code proactiveTrainingEnabled} 控制。 */
    public void start() {
        if (!taskConfig.isProactiveTrainingEnabled()) {
            log.debug("主动训练调度: 已关闭");
            return;
        }
        long intervalSec = Math.max(1, taskConfig.getProactiveTrainingReplayIntervalDays()) * 86400L;
        scheduler.scheduleAtFixedRate(this::runSafely,
                INITIAL_DELAY_SECONDS, intervalSec, TimeUnit.SECONDS);
        log.info("主动训练调度已启动: intervalDays={}", taskConfig.getProactiveTrainingReplayIntervalDays());
    }

    /**
     * 单次运行 — 构造 few-shot 样例库，保存到 {@link ProactiveFewShotLibrary}。
     *
     * <p>访问级别 public 便于测试与运维手动触发。</p>
     */
    public void run() {
        String userId = resolveUserId();
        if (userId == null) {
            log.debug("主动训练调度: 无 defaultUserId，跳过");
            return;
        }
        Instant since = Instant.now().minus(
                Duration.ofDays(taskConfig.getProactiveTrainingReplayIntervalDays() * 2L));
        List<ProactiveFewShotSample> samples =
                replayService.buildFewShotSamples(userId, since, SAMPLE_QUERY_LIMIT);
        library.save(userId, samples);
        log.info("主动训练回放完成: user={}, samples={}", userId, samples.size());
    }

    private void runSafely() {
        try {
            run();
        } catch (Exception e) {
            log.warn("主动训练调度异常: {}", e.getMessage(), e);
        }
    }

    @Nullable
    private String resolveUserId() {
        if (notificationProperties == null) return null;
        String uid = notificationProperties.getDefaultUserId();
        return (uid == null || uid.isBlank()) ? null : uid;
    }
}
