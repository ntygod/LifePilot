package com.lifepilot.scheduler.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时任务调度器配置属性。
 *
 * <p>绑定 {@code lifepilot.scheduler} 配置前缀。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.scheduler")
public class SchedulerProperties {

    /** 调度器总开关，默认 true。 */
    private boolean enabled = true;

    /** 调度线程池核心大小，默认 2。 */
    private int corePoolSize = 2;

    /** 启动时是否自动恢复 PENDING 任务，默认 true。 */
    private boolean recoveryOnStartup = true;

    /** 日程提醒提前时间（分钟），默认 15。 */
    private int scheduleReminderMinutes = 15;
}
