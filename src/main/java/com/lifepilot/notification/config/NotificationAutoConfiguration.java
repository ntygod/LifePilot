package com.lifepilot.notification.config;

import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.interaction.channel.converter.MessageConverter;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.notification.DefaultNotificationService;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationScheduler;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.PassiveNotificationQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * 通知模块自动配置。
 *
 * <p>注册通知模块所有核心 Bean：NotificationRepository、PassiveNotificationQueue、
 * NotificationService、NotificationScheduler。
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
    public PassiveNotificationQueue passiveNotificationQueue(NotificationRepository notificationRepository) {
        log.info("通知模块: 注册 PassiveNotificationQueue");
        var queue = new PassiveNotificationQueue(notificationRepository);
        queue.loadUndelivered();
        return queue;
    }

    @Bean
    @ConditionalOnMissingBean
    public NotificationService notificationService(List<ChannelAdapter> channelAdapters,
                                                    List<MessageConverter> messageConverters,
                                                    NotificationRepository notificationRepository,
                                                    PassiveNotificationQueue passiveNotificationQueue,
                                                    NotificationProperties properties) {
        log.info("通知模块: 注册 DefaultNotificationService");
        return new DefaultNotificationService(channelAdapters, messageConverters,
                notificationRepository, passiveNotificationQueue, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public NotificationScheduler notificationScheduler(PassiveNotificationQueue passiveNotificationQueue,
                                                        @Nullable SseSessionManager sseSessionManager,
                                                        NotificationProperties properties) {
        log.info("通知模块: 注册 NotificationScheduler");
        return new NotificationScheduler(passiveNotificationQueue, sseSessionManager, properties);
    }
}
