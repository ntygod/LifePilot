package com.lifepilot.config.threadpool;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 线程池配置属性。
 *
 * @author zsg
 * @since 2026-03-18
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.thread-pool")
public class ThreadPoolProperties {

    /** 共享调度器配置。 */
    private Shared shared = new Shared();

    /** 优雅关闭超时时间（秒）。 */
    private int shutdownTimeoutSeconds = 10;

    /**
     * 共享调度器分组配置。
     */
    @Setter
    @Getter
    public static class Shared {

        /** cleanup 分组 corePoolSize。 */
        private int cleanupCorePoolSize = 1;

        /** debounce 分组 corePoolSize。 */
        private int debounceCorePoolSize = 1;

        /** heartbeat 分组 corePoolSize。 */
        private int heartbeatCorePoolSize = 1;

    }
}
