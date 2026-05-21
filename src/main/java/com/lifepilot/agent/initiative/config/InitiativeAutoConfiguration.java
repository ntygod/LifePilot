package com.lifepilot.agent.initiative.config;

import com.lifepilot.agent.initiative.InitiativeEngine;
import com.lifepilot.agent.initiative.Thinker;
import com.lifepilot.agent.initiative.execute.ActionExecutor;
import com.lifepilot.agent.initiative.execute.ExecutionPermissionRepository;
import com.lifepilot.agent.initiative.express.ConversationInitiator;
import com.lifepilot.agent.initiative.gate.Gatekeeper;
import com.lifepilot.agent.initiative.pool.ThoughtPool;
import com.lifepilot.agent.initiative.pool.ThoughtRepository;
import com.lifepilot.agent.initiative.signal.InitiativeEventListener;
import com.lifepilot.agent.initiative.thinker.DefaultThinker;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.time.LocalTime;

/**
 * 主动引擎自动装配。
 *
 * @author zsg
 * @since 2026-06-01
 */
@AutoConfiguration
@EnableConfigurationProperties(InitiativeProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.initiative", name = "enabled",
        havingValue = "true")
public class InitiativeAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InitiativeAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ThoughtPool thoughtPool(InitiativeProperties props) {
        log.info("主动引擎: 注册 ThoughtPool, maxActive={}", props.getMaxActiveThoughts());
        return new ThoughtPool(
                props.getMaxActiveThoughts(),
                Duration.ofHours(props.getBrewingTtlHours()),
                Duration.ofHours(props.getReadyTtlHours())
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public Gatekeeper gatekeeper(InitiativeProperties props) {
        log.info("主动引擎: 注册 Gatekeeper, dailyMax={}, minInterval={}min",
                props.getDailyMaxExpressions(), props.getMinIntervalMinutes());
        return new Gatekeeper(
                props.getDailyMaxExpressions(),
                Duration.ofMinutes(props.getMinIntervalMinutes()),
                LocalTime.parse(props.getQuietHoursStart()),
                LocalTime.parse(props.getQuietHoursEnd())
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public InitiativeEngine initiativeEngine(ThoughtPool thoughtPool,
                                             Gatekeeper gatekeeper,
                                             @Nullable Thinker thinker) {
        log.info("主动引擎: 注册 InitiativeEngine, thinker={}",
                thinker != null ? "available" : "unavailable");
        return new InitiativeEngine(thoughtPool, gatekeeper, thinker);
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultThinker defaultThinker(@Nullable SemanticMemory semanticMemory) {
        log.info("主动引擎: 注册 DefaultThinker, semanticMemory={}",
                semanticMemory != null ? "available" : "unavailable");
        return new DefaultThinker(semanticMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationInitiator conversationInitiator(
            @Nullable com.lifepilot.agent.orchestration.AgentOrchestrator agentOrchestrator) {
        log.info("主动引擎: 注册 ConversationInitiator, orchestrator={}",
                agentOrchestrator != null ? "available" : "unavailable");
        return new ConversationInitiator(agentOrchestrator);
    }

    @Bean
    @ConditionalOnMissingBean
    public InitiativeEventListener initiativeEventListener(InitiativeEngine engine) {
        log.info("主动引擎: 注册 InitiativeEventListener");
        return new InitiativeEventListener(engine);
    }

    @Bean
    @ConditionalOnMissingBean
    public ThoughtRepository thoughtRepository(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                               ObjectMapper objectMapper) {
        log.info("主动引擎: 注册 ThoughtRepository");
        return new ThoughtRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExecutionPermissionRepository executionPermissionRepository(
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        log.info("主动引擎: 注册 ExecutionPermissionRepository");
        return new ExecutionPermissionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionExecutor actionExecutor(
            @Nullable com.lifepilot.agent.orchestration.AgentOrchestrator agentOrchestrator) {
        log.info("主动引擎: 注册 ActionExecutor, orchestrator={}",
                agentOrchestrator != null ? "available" : "unavailable");
        return new ActionExecutor(agentOrchestrator);
    }
}
