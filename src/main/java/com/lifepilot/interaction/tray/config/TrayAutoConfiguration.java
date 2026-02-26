package com.lifepilot.interaction.tray.config;

import com.lifepilot.interaction.tray.TrayManager;
import com.lifepilot.interaction.tray.TrayNotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * 系统托盘 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.tray.enabled=true} 激活（由 {@code --mode=tray} 自动设置），
 * 注册 {@link TrayManager} 和 {@link TrayNotificationChannel} Bean。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.tray.enabled", havingValue = "true")
@EnableConfigurationProperties(TrayConfigProperties.class)
public class TrayAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TrayAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public TrayManager trayManager(TrayConfigProperties config,
                                    ConfigurableApplicationContext context) {
        log.info("系统托盘: 自动配置激活");
        return new TrayManager(config, context);
    }

    @Bean
    @ConditionalOnMissingBean
    public TrayNotificationChannel trayNotificationChannel(TrayManager trayManager) {
        return new TrayNotificationChannel(trayManager);
    }
}
