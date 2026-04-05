package com.lifepilot.agent.task.config;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.tracking.SmsSignalExtractor;
import com.lifepilot.agent.task.reminder.tracking.TrackingRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部追踪数据源自动配置。
 *
 * <p>注册 TrackingRegistry、SmsSignalExtractor 等 Bean。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
@AutoConfiguration(after = ReminderAutoConfiguration.class)
@ConditionalOnProperty(prefix = "lifepilot.agent.task", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TrackingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TrackingRegistry trackingRegistry(JdbcTemplate jdbcTemplate) {
        return new TrackingRegistry(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SmsSignalExtractor smsSignalExtractor(TrackingRegistry trackingRegistry) {
        return new SmsSignalExtractor(trackingRegistry);
    }
}
