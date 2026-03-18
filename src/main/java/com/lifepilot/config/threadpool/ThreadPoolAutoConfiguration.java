package com.lifepilot.config.threadpool;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 线程池基础设施自动装配。
 *
 * <p>在 AutoConfiguration.imports 中排在第一行，确保在所有业务模块之前加载。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
@AutoConfiguration
@EnableConfigurationProperties(ThreadPoolProperties.class)
public class ThreadPoolAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ThreadPoolRegistry threadPoolRegistry() {
        return new ThreadPoolRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public SharedScheduler sharedScheduler(ThreadPoolRegistry registry,
                                            ThreadPoolProperties properties) {
        return new SharedScheduler(registry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public VirtualThreadExecutorFactory virtualThreadExecutorFactory(
            ThreadPoolRegistry registry) {
        return new VirtualThreadExecutorFactory(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ThreadPoolLifecycleManager threadPoolLifecycleManager(
            ThreadPoolRegistry registry,
            ThreadPoolProperties properties) {
        return new ThreadPoolLifecycleManager(registry, properties);
    }
}
