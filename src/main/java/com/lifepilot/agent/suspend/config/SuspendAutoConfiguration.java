package com.lifepilot.agent.suspend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.model.SuspendReason;
import com.lifepilot.agent.suspend.AgentResumeListener;
import com.lifepilot.agent.suspend.SuspendProperties;
import com.lifepilot.agent.suspend.event.ScheduledWakeupEvent;
import com.lifepilot.agent.suspend.model.SuspendedAgent;
import com.lifepilot.agent.suspend.store.SqliteSuspendStore;
import com.lifepilot.agent.suspend.store.SuspendStore;
import com.lifepilot.config.threadpool.SharedScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 挂起-恢复模块自动装配。
 *
 * <p>注册 {@link SqliteSuspendStore}、{@link AgentResumeListener}，
 * 启动过期清理定时任务，并在应用启动时重新注册 ScheduledWakeup 延迟任务。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@AutoConfiguration
@EnableConfigurationProperties(SuspendProperties.class)
public class SuspendAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SuspendAutoConfiguration.class);

    private final SharedScheduler sharedScheduler;

    public SuspendAutoConfiguration(SharedScheduler sharedScheduler) {
        this.sharedScheduler = sharedScheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    public SuspendStore suspendStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        log.info("挂起-恢复: 注册 SqliteSuspendStore");
        return new SqliteSuspendStore(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ReactAgentLoop.class, SuspendStore.class})
    public AgentResumeListener agentResumeListener(ReactAgentLoop agentLoop, SuspendStore suspendStore) {
        log.info("挂起-恢复: 注册 AgentResumeListener");
        return new AgentResumeListener(agentLoop, suspendStore);
    }

    /**
     * 启动过期清理定时任务 + 重新注册 ScheduledWakeup 延迟任务。
     *
     * <p>在 Spring Context 刷新完成后执行：
     * <ol>
     *   <li>按 {@code cleanupInterval} 周期调用 {@code cleanExpired(maxAge)}</li>
     *   <li>扫描 reason_type="ScheduledWakeup" 的记录，重新注册延迟任务或立即发布事件</li>
     * </ol>
     */
    @EventListener
    public void onContextRefreshed(ContextRefreshedEvent event) {
        var context = event.getApplicationContext();
        var suspendStore = context.getBean(SuspendStore.class);
        var properties = context.getBean(SuspendProperties.class);
        // ApplicationContext 本身实现了 ApplicationEventPublisher，直接使用即可
        ApplicationEventPublisher eventPublisher = context;

        // 启动过期清理定时任务
        long intervalMs = properties.getCleanupInterval().toMillis();
        Duration maxAge = properties.getMaxAge();
        sharedScheduler.cleanup().scheduleAtFixedRate(() -> {
            try {
                int deleted = suspendStore.cleanExpired(maxAge);
                if (deleted > 0) {
                    log.info("挂起记录过期清理完成: 删除数量={}", deleted);
                }
            } catch (Exception e) {
                log.error("挂起记录过期清理失败", e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("挂起-恢复: 过期清理定时任务已启动（间隔={}）", properties.getCleanupInterval());

        // 重新注册 ScheduledWakeup 延迟任务
        reRegisterScheduledWakeups(suspendStore, eventPublisher);
    }

    /** 扫描 ScheduledWakeup 记录，重新注册延迟任务或立即发布事件。 */
    private void reRegisterScheduledWakeups(SuspendStore suspendStore,
                                            ApplicationEventPublisher eventPublisher) {
        List<SuspendedAgent> wakeups = suspendStore.findByReasonType("ScheduledWakeup");
        if (wakeups.isEmpty()) {
            return;
        }
        log.info("挂起-恢复: 发现 {} 个 ScheduledWakeup 记录，重新注册延迟任务", wakeups.size());

        for (var sa : wakeups) {
            if (sa.suspendReason() instanceof SuspendReason.ScheduledWakeup sw) {
                Duration delay = Duration.between(Instant.now(), sw.wakeupAt());
                String traceId = sa.traceId();
                if (delay.isNegative() || delay.isZero()) {
                    // 唤醒时间已过，立即发布
                    eventPublisher.publishEvent(new ScheduledWakeupEvent(traceId, Instant.now()));
                    log.info("ScheduledWakeup 唤醒时间已过，立即发布恢复事件: traceId={}", traceId);
                } else {
                    sharedScheduler.cleanup().schedule(() -> {
                        eventPublisher.publishEvent(new ScheduledWakeupEvent(traceId, Instant.now()));
                        log.info("ScheduledWakeup 延迟任务触发（启动恢复）: traceId={}", traceId);
                    }, delay.toMillis(), TimeUnit.MILLISECONDS);
                    log.info("ScheduledWakeup 延迟任务已重新注册: traceId={}, wakeupAt={}, delayMs={}",
                            traceId, sw.wakeupAt(), delay.toMillis());
                }
            }
        }
    }
}
