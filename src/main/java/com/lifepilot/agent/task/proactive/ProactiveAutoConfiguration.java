package com.lifepilot.agent.task.proactive;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.reminder.ReminderFocusStateHolder;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 主动引擎框架自动配置。
 *
 * <p>注册 DecisionGate、DeliveryEngine、QueuedActionRepository、ProactiveEngine。
 * 行为插件（如 ReminderBehavior）由各自的自动配置类注册。</p>
 *
 * @author zsg
 * @since 2026-04-14
 */
@AutoConfiguration
public class ProactiveAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QueuedActionRepository queuedActionRepository(JdbcTemplate jdbcTemplate) {
        return new QueuedActionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public DecisionGate proactiveDecisionGate() {
        return new DecisionGate();
    }

    @Bean
    @ConditionalOnMissingBean
    public DeliveryEngine proactiveDeliveryEngine(NotificationService notificationService,
                                                   QueuedActionRepository queuedActionRepository) {
        return new DeliveryEngine(notificationService, queuedActionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ProactiveBehavior.class)
    public ProactiveEngine proactiveEngine(List<ProactiveBehavior> behaviors,
                                           DecisionGate decisionGate,
                                           DeliveryEngine deliveryEngine,
                                           NotificationProperties notificationProperties,
                                           NotificationRepository notificationRepository,
                                           AgentConfigProperties config,
                                           @Autowired(required = false) ReminderFocusStateHolder focusStateHolder) {
        return new ProactiveEngine(behaviors, decisionGate, deliveryEngine,
                notificationProperties, notificationRepository, config, focusStateHolder);
    }
}
