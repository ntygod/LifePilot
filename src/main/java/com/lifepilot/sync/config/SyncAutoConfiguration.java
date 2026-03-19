package com.lifepilot.sync.config;

import com.lifepilot.sync.skill.SyncToolProvider;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

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

    private static final Logger log = LoggerFactory.getLogger(SyncAutoConfiguration.class);

    /**
     * 注册同步工具提供者。
     */
    @Bean
    SyncToolProvider syncToolProvider() {
        return new SyncToolProvider();
    }

    // ==================== 启动后工具注册 ====================

    /**
     * 应用启动完成后注册同步模块工具到 DynamicToolRegistry。
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void registerTools(ApplicationReadyEvent event) {
        var ctx = event.getApplicationContext();
        var toolRegistry = ctx.getBean(DynamicToolRegistry.class);

        ctx.getBean(SyncToolProvider.class).registerTools(toolRegistry);

        log.info("同步模块工具注册完成");
    }
}
