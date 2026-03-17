package com.lifepilot.agent.suspend;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 挂起-恢复配置属性。
 *
 * <p>通过 {@code lifepilot.agent.suspend.*} 配置键外部化，
 * 支持挂起记录最大保留时长和过期清理间隔两个核心参数。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@ConfigurationProperties(prefix = "lifepilot.agent.suspend")
public class SuspendProperties {

    /** 挂起记录最大保留时长，默认 24 小时。超过此时长的记录将被清理。 */
    private Duration maxAge = Duration.ofHours(24);

    /** 过期清理定时任务间隔，默认 1 小时。 */
    private Duration cleanupInterval = Duration.ofHours(1);

    public Duration getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(Duration maxAge) {
        this.maxAge = maxAge;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }
}
