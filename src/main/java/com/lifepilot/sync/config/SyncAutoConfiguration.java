package com.lifepilot.sync.config;

import com.lifepilot.sync.skill.SyncToolProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 数据同步模块 Spring Boot 自动配置。
 *
 * <p>注册同步模块核心 Bean：SyncToolProvider。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
@AutoConfiguration
public class SyncAutoConfiguration {

    /**
     * 注册同步工具提供者。
     */
    @Bean
    SyncToolProvider syncToolProvider() {
        return new SyncToolProvider();
    }
}
