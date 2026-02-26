package com.lifepilot.agent.proactive.config;

import com.lifepilot.agent.proactive.FrequencyStateManager;
import com.lifepilot.agent.proactive.NotificationDispatcher;
import com.lifepilot.agent.proactive.ProactiveReasoner;
import com.lifepilot.agent.proactive.ResponseTracker;
import com.lifepilot.agent.proactive.RuleEngine;
import com.lifepilot.agent.proactive.SignalCollector;
import com.lifepilot.agent.proactive.channel.LogNotificationChannel;
import com.lifepilot.agent.proactive.channel.NotificationChannel;
import com.lifepilot.agent.proactive.channel.PassiveNotificationQueue;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.episodic.EpisodicMemory;

import java.util.List;
import com.lifepilot.skill.builtin.habit.HabitRepository;
import com.lifepilot.skill.builtin.schedule.ScheduleRepository;
import com.lifepilot.skill.builtin.todo.TodoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 主动推理引擎 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.agent.proactive.enabled=true}（默认）激活，
 * 注册所有主动推理相关 Bean。依赖 Agent 引擎、记忆系统和内置技能模块。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
@AutoConfiguration
@EnableConfigurationProperties(ProactiveConfigProperties.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "lifepilot.agent.proactive", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ProactiveAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProactiveAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public LogNotificationChannel logNotificationChannel() {
        return new LogNotificationChannel();
    }

    @Bean
    @ConditionalOnMissingBean
    public PassiveNotificationQueue passiveNotificationQueue() {
        return new PassiveNotificationQueue();
    }

    @Bean
    @ConditionalOnMissingBean
    public FrequencyStateManager frequencyStateManager(JdbcTemplate jdbcTemplate,
                                                        ProactiveConfigProperties config) {
        var manager = new FrequencyStateManager(jdbcTemplate, config);
        manager.loadPersistedStates();
        log.info("主动推理: FrequencyStateManager 初始化完成，已加载持久化状态");
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({TodoRepository.class, ScheduleRepository.class,
            HabitRepository.class, EpisodicMemory.class})
    public SignalCollector signalCollector(TodoRepository todoRepository,
                                           ScheduleRepository scheduleRepository,
                                           HabitRepository habitRepository,
                                           EpisodicMemory episodicMemory,
                                           JdbcTemplate jdbcTemplate) {
        return new SignalCollector(todoRepository, scheduleRepository,
                habitRepository, episodicMemory, jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public RuleEngine ruleEngine(FrequencyStateManager frequencyStateManager,
                                  ProactiveConfigProperties config) {
        return new RuleEngine(frequencyStateManager, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public NotificationDispatcher notificationDispatcher(List<NotificationChannel> channels,
                                                          PassiveNotificationQueue passiveQueue,
                                                          JdbcTemplate jdbcTemplate) {
        return new NotificationDispatcher(channels, passiveQueue, jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ResponseTracker responseTracker(FrequencyStateManager frequencyStateManager,
                                            ProactiveConfigProperties config) {
        return new ResponseTracker(frequencyStateManager, config);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SignalCollector.class)
    public ProactiveReasoner proactiveReasoner(SignalCollector signalCollector,
                                                RuleEngine ruleEngine,
                                                FrequencyStateManager frequencyStateManager,
                                                NotificationDispatcher notificationDispatcher,
                                                ResponseTracker responseTracker,
                                                LlmRouter llmRouter,
                                                ProactiveConfigProperties config) {
        log.info("主动推理引擎初始化完成");
        return new ProactiveReasoner(signalCollector, ruleEngine, frequencyStateManager,
                notificationDispatcher, responseTracker, llmRouter, config);
    }
}
