package com.lifepilot.notification.config;

import com.lifepilot.interaction.runtime.ChannelDeliveryDispatcher;
import com.lifepilot.interaction.runtime.ChannelUserMappingCache;
import com.lifepilot.interaction.service.ChannelInstanceService;
import com.lifepilot.notification.DefaultNotificationService;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
/**
 * 通知模块自动配置。
 *
 * <p>注册通知模块核心 Bean：NotificationRepository、NotificationService。
 * 通过 {@code lifepilot.notification.enabled} 控制总开关，默认启用。
 *
 * @author zsg
 * @since 2026-03-13
 */
@AutoConfiguration
@EnableConfigurationProperties(NotificationProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.notification", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class NotificationAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NotificationAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public NotificationRepository notificationRepository(JdbcTemplate jdbcTemplate) {
        log.info("通知模块: 注册 NotificationRepository");
        return new NotificationRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public NotificationService notificationService(ChannelInstanceService channelInstanceService,
                                                   ChannelDeliveryDispatcher channelDeliveryDispatcher,
                                                   NotificationRepository notificationRepository,
                                                   NotificationProperties properties,
                                                   @Autowired(required = false) ChannelUserMappingCache userMappingCache) {
        log.info("通知模块: 注册 DefaultNotificationService");
        return new DefaultNotificationService(
                channelInstanceService,
                channelDeliveryDispatcher,
                notificationRepository,
                properties,
                userMappingCache
        );
    }
}
