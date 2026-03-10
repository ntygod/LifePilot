package com.lifepilot.agent.proactive.config;

import com.lifepilot.agent.proactive.FrequencyStateManager;
import com.lifepilot.agent.proactive.NotificationDispatcher;
import com.lifepilot.agent.proactive.NotificationTypeRegistry;
import com.lifepilot.agent.proactive.ProactiveReasoner;
import com.lifepilot.agent.proactive.ResponseTracker;
import com.lifepilot.agent.proactive.PolicyEngine;
import com.lifepilot.agent.proactive.SignalCollector;
import com.lifepilot.agent.proactive.candidate.CandidateProvider;
import com.lifepilot.agent.proactive.candidate.DailySummaryCandidateProvider;
import com.lifepilot.agent.proactive.candidate.WeeklyReviewCandidateProvider;
import com.lifepilot.agent.proactive.channel.GatewayNotificationChannel;
import com.lifepilot.agent.proactive.channel.LogNotificationChannel;
import com.lifepilot.agent.proactive.channel.NotificationChannel;
import com.lifepilot.agent.proactive.channel.PassiveNotificationQueue;
import com.lifepilot.agent.proactive.signal.SignalSource;
import com.lifepilot.interaction.channel.ChannelAdapter;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.builtin.BuiltinSkillRegistrar;

import java.util.ArrayList;
import java.util.List;
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
    @ConditionalOnBean(ChannelAdapter.class)
    public GatewayNotificationChannel gatewayNotificationChannel(List<ChannelAdapter> adapters) {
        return new GatewayNotificationChannel(adapters);
    }

    @Bean
    @ConditionalOnMissingBean
    public PassiveNotificationQueue passiveNotificationQueue() {
        return new PassiveNotificationQueue();
    }

    @Bean
    @ConditionalOnMissingBean
    public NotificationTypeRegistry notificationTypeRegistry() {
        var registry = new NotificationTypeRegistry();
        log.info("主动推理: NotificationTypeRegistry 初始化完成，已注册 {} 种默认类型", registry.listAll().size());
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public FrequencyStateManager frequencyStateManager(JdbcTemplate jdbcTemplate,
                                                        ProactiveConfigProperties config,
                                                        NotificationTypeRegistry typeRegistry) {
        var manager = new FrequencyStateManager(jdbcTemplate, config, typeRegistry);
        manager.loadPersistedStates();
        log.info("主动推理: FrequencyStateManager 初始化完成，已加载持久化状态");
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({BuiltinSkillRegistrar.class, EpisodicMemory.class})
    public SignalCollector signalCollector(BuiltinSkillRegistrar builtinSkillRegistrar,
                                           EpisodicMemory episodicMemory) {
        // 合并来自 BuiltinSkillRegistrar 的信号源
        List<SignalSource> allSignalSources = new ArrayList<>(builtinSkillRegistrar.getRegisteredSignalSources());
        log.info("主动推理: SignalCollector 初始化，信号源数量={}", allSignalSources.size());
        return new SignalCollector(allSignalSources, episodicMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    public DailySummaryCandidateProvider dailySummaryCandidateProvider(ProactiveConfigProperties config) {
        return new DailySummaryCandidateProvider(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public WeeklyReviewCandidateProvider weeklyReviewCandidateProvider(ProactiveConfigProperties config) {
        return new WeeklyReviewCandidateProvider(config);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(BuiltinSkillRegistrar.class)
    public PolicyEngine policyEngine(BuiltinSkillRegistrar builtinSkillRegistrar,
                                      DailySummaryCandidateProvider dailySummaryProvider,
                                      WeeklyReviewCandidateProvider weeklyReviewProvider,
                                      FrequencyStateManager frequencyStateManager,
                                      ProactiveConfigProperties config) {
        // 合并来自 BuiltinSkillRegistrar 的候选提供者 + 独立候选提供者
        List<CandidateProvider> allProviders = new ArrayList<>(builtinSkillRegistrar.getRegisteredCandidateProviders());
        allProviders.add(dailySummaryProvider);
        allProviders.add(weeklyReviewProvider);
        log.info("主动推理: PolicyEngine 初始化，候选提供者数量={}", allProviders.size());
        return new PolicyEngine(allProviders, frequencyStateManager, config);
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
                                            ProactiveConfigProperties config,
                                            NotificationTypeRegistry typeRegistry) {
        return new ResponseTracker(frequencyStateManager, config, typeRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SignalCollector.class)
    public ProactiveReasoner proactiveReasoner(SignalCollector signalCollector,
                                                PolicyEngine policyEngine,
                                                FrequencyStateManager frequencyStateManager,
                                                NotificationDispatcher notificationDispatcher,
                                                ResponseTracker responseTracker,
                                                LlmRouter llmRouter,
                                                ProactiveConfigProperties config,
                                                PromptRegistry promptRegistry) {
        log.info("主动推理引擎初始化完成");
        return new ProactiveReasoner(signalCollector, policyEngine, frequencyStateManager,
                notificationDispatcher, responseTracker, llmRouter, config, promptRegistry);
    }
}
