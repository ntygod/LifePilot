package com.lifepilot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.*;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.context.DefaultMemoryRetrievalStrategy;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.agent.trace.TraceRecorder;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Agent 引擎 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.agent.enabled=true}（默认）激活，
 * 注册所有 Agent 核心 Bean，每个 Bean 使用
 * {@link ConditionalOnMissingBean} 允许用户覆盖。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentConfigProperties.class)
@ConditionalOnProperty(prefix = "lifepilot.agent", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public StateReducer stateReducer() {
        return new StateReducer();
    }

    @Bean
    @ConditionalOnBean({HybridRetriever.class, WorkingMemory.class})
    @ConditionalOnMissingBean(ContextAssembler.class)
    public ContextAssembler fullContextAssembler(AgentConfigProperties config,
                                                  HybridRetriever hybridRetriever,
                                                  WorkingMemory workingMemory,
                                                  TokenBudgetAllocator tokenBudgetAllocator) {
        log.info("Agent 引擎: 注册完整版 ContextAssembler（记忆系统已就绪）");
        var strategy = new DefaultMemoryRetrievalStrategy();
        return new ContextAssembler(config, hybridRetriever,
                workingMemory, tokenBudgetAllocator, strategy);
    }

    @Bean
    @ConditionalOnMissingBean(ContextAssembler.class)
    public ContextAssembler basicContextAssembler(AgentConfigProperties config) {
        log.warn("Agent 引擎: 注册基础版 ContextAssembler（记忆系统不可用，记忆检索功能已降级）");
        return new ContextAssembler(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceRecorder traceRecorder(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new TraceRecorder(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager(JdbcTemplate jdbcTemplate,
                                          ObjectMapper objectMapper,
                                          AgentConfigProperties config) {
        return new SessionManager(jdbcTemplate, objectMapper, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionParser actionParser(ObjectMapper objectMapper) {
        return new ActionParser(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentToolProvider agentToolProvider() {
        return new NoOpAgentToolProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentLoop agentLoop(StateReducer stateReducer,
                                ContextAssembler contextAssembler,
                                LlmRouter llmRouter,
                                TraceRecorder traceRecorder,
                                SessionManager sessionManager,
                                ActionParser actionParser,
                                AgentToolProvider agentToolProvider,
                                AgentConfigProperties config) {
        log.info("Agent 引擎初始化完成");
        return new AgentLoop(stateReducer, contextAssembler, llmRouter,
                traceRecorder, sessionManager, actionParser, agentToolProvider, config);
    }
}
