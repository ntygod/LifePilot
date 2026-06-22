package com.lifepilot.agent.initiative.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentAutoConfiguration;
import com.lifepilot.agent.initiative.InitiativeEngine;
import com.lifepilot.agent.initiative.Thinker;
import com.lifepilot.agent.initiative.express.ConversationInitiator;
import com.lifepilot.agent.initiative.gate.Gatekeeper;
import com.lifepilot.agent.initiative.pool.ThoughtPool;
import com.lifepilot.agent.initiative.pool.ThoughtRepository;
import com.lifepilot.agent.initiative.signal.InitiativeEventListener;
import com.lifepilot.agent.initiative.thinker.DefaultThinker;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.memory.config.MemoryAutoConfiguration;
import com.lifepilot.memory.consumption.attention.MemoryAttentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.time.Clock;
import java.time.LocalTime;

/**
 * 主动引擎自动装配。
 *
 * @author zsg
 * @since 2026-06-01
 */
@AutoConfiguration(after = {AgentAutoConfiguration.class, MemoryAutoConfiguration.class})
@EnableConfigurationProperties(InitiativeProperties.class)
@ConditionalOnBean({AgentOrchestrator.class, MemoryAttentionService.class})
@ConditionalOnProperty(prefix = "lifepilot.initiative", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class InitiativeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InitiativeAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public com.lifepilot.agent.initiative.maturity.MaturityModel maturityModel(InitiativeProperties props) {
        var m = props.getMaturity();
        log.info("主动引擎: 注册 MaturityModel, readyThreshold={}, halfLife={}h",
                m.getReadyThreshold(), m.getDecayHalfLifeHours());
        return new com.lifepilot.agent.initiative.maturity.MaturityModel(
                new com.lifepilot.agent.initiative.maturity.MaturityModel.Config(
                        m.getReadyThreshold(), m.getDemoteThreshold(), m.getDismissFloor(),
                        m.getReinforceBaseGain(), m.getDecayHalfLifeHours(),
                        m.getDecayGraceHours(), m.getDeadlinePullWindowHours()));
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock initiativeClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @ConditionalOnMissingBean
    public ThoughtPool thoughtPool(InitiativeProperties props, ThoughtRepository thoughtRepository,
                                   com.lifepilot.agent.initiative.maturity.MaturityModel maturityModel,
                                   Clock clock) {
        log.info("主动引擎: 注册 ThoughtPool, maxActive={}", props.getMaxActiveThoughts());
        return new ThoughtPool(
                props.getMaxActiveThoughts(),
                Duration.ofHours(props.getBrewingTtlHours()),
                Duration.ofHours(props.getReadyTtlHours()),
                thoughtRepository,
                maturityModel,
                clock
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public Gatekeeper gatekeeper(InitiativeProperties props, Clock clock) {
        log.info("主动引擎: 注册 Gatekeeper, dailyMax={}, minInterval={}min",
                props.getDailyMaxExpressions(), props.getMinIntervalMinutes());
        return new Gatekeeper(
                props.getDailyMaxExpressions(),
                Duration.ofMinutes(props.getMinIntervalMinutes()),
                LocalTime.parse(props.getQuietHoursStart()),
                LocalTime.parse(props.getQuietHoursEnd()),
                clock
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public InitiativeEngine initiativeEngine(ThoughtPool thoughtPool,
                                             Gatekeeper gatekeeper,
                                             Thinker thinker,
                                             ConversationInitiator conversationInitiator,
                                             Clock clock) {
        log.info("主动引擎: 注册 InitiativeEngine");
        return new InitiativeEngine(thoughtPool, gatekeeper, thinker, conversationInitiator, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultThinker defaultThinker(MemoryAttentionService memoryAttentionService,
                                         InitiativeProperties props,
                                         Clock clock) {
        log.info("主动引擎: 注册 DefaultThinker");
        return new DefaultThinker(memoryAttentionService, props.getMaturity().getReadyThreshold(), clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationInitiator conversationInitiator(AgentOrchestrator agentOrchestrator) {
        log.info("主动引擎: 注册 ConversationInitiator");
        return new ConversationInitiator(agentOrchestrator);
    }

    @Bean
    @ConditionalOnMissingBean
    public InitiativeEventListener initiativeEventListener(InitiativeEngine engine, Clock clock) {
        log.info("主动引擎: 注册 InitiativeEventListener");
        return new InitiativeEventListener(engine, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public ThoughtRepository thoughtRepository(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                               ObjectMapper objectMapper) {
        log.info("主动引擎: 注册 ThoughtRepository");
        return new ThoughtRepository(jdbcTemplate, objectMapper);
    }
}
