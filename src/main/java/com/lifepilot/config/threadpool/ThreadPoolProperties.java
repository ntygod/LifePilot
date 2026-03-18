package com.lifepilot.config.threadpool;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 线程池配置属性。
 *
 * @author zsg
 * @since 2026-03-18
 */
@ConfigurationProperties(prefix = "lifepilot.thread-pool")
public class ThreadPoolProperties {

    /** 共享调度器配置。 */
    private Shared shared = new Shared();

    /** 优雅关闭超时时间（秒）。 */
    private int shutdownTimeoutSeconds = 10;

    public Shared getShared() {
        return shared;
    }

    public void setShared(Shared shared) {
        this.shared = shared;
    }

    public int getShutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    /**
     * 共享调度器分组配置。
     */
    public static class Shared {

        /** cleanup 分组 corePoolSize。 */
        private int cleanupCorePoolSize = 1;

        /** debounce 分组 corePoolSize。 */
        private int debounceCorePoolSize = 1;

        /** heartbeat 分组 corePoolSize。 */
        private int heartbeatCorePoolSize = 1;

        public int getCleanupCorePoolSize() {
            return cleanupCorePoolSize;
        }

        public void setCleanupCorePoolSize(int cleanupCorePoolSize) {
            this.cleanupCorePoolSize = cleanupCorePoolSize;
        }

        public int getDebounceCorePoolSize() {
            return debounceCorePoolSize;
        }

        public void setDebounceCorePoolSize(int debounceCorePoolSize) {
            this.debounceCorePoolSize = debounceCorePoolSize;
        }

        public int getHeartbeatCorePoolSize() {
            return heartbeatCorePoolSize;
        }

        public void setHeartbeatCorePoolSize(int heartbeatCorePoolSize) {
            this.heartbeatCorePoolSize = heartbeatCorePoolSize;
        }
    }
}
