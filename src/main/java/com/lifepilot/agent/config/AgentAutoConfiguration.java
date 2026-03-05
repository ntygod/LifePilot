package com.lifepilot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.ActionParser;
import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.StateReducer;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.context.DefaultMemoryRetrievalStrategy;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.conversation.DefaultConversationViewService;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.knowledge.repository.DocumentRepository;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.knowledge.retrieve.DocumentRetriever;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.tool.config.ToolAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

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
@AutoConfiguration(after = ToolAutoConfiguration.class)
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
    @ConditionalOnMissingBean(ContextAssembler.class)
    public ContextAssembler contextAssembler(AgentConfigProperties config,
                                             @Autowired(required = false) HybridRetriever hybridRetriever,
                                             @Autowired(required = false) WorkingMemory workingMemory,
                                             @Autowired(required = false) TokenBudgetAllocator tokenBudgetAllocator,
                                             @Autowired(required = false) DataRedactor dataRedactor,
                                             @Autowired(required = false) DocumentRetriever documentRetriever,
                                             @Autowired(required = false) SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                                             @Autowired(required = false) DocumentRepository documentRepository) {
        if (hybridRetriever != null && workingMemory != null && tokenBudgetAllocator != null) {
            log.info("Agent 引擎: 注册完整版 ContextAssembler（记忆系统已就绪）");
            var strategy = new DefaultMemoryRetrievalStrategy();
            return new ContextAssembler(config, hybridRetriever,
                    workingMemory, tokenBudgetAllocator, strategy, dataRedactor,
                    documentRetriever, sessionKnowledgeBaseRepository, documentRepository, null, null);
        }
        log.warn("Agent 引擎: 注册基础版 ContextAssembler（记忆系统部分或全部不可用，记忆检索功能已降级）");
        return new ContextAssembler(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager(JdbcTemplate jdbcTemplate,
                                          ObjectMapper objectMapper,
                                          AgentConfigProperties config,
                                          @Autowired(required = false) com.lifepilot.memory.working.WorkingMemory workingMemory) {
        return new SessionManager(jdbcTemplate, objectMapper, config, workingMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationViewService conversationViewService(SessionManager sessionManager,
                                                           EpisodicMemory episodicMemory) {
        return new DefaultConversationViewService(sessionManager, episodicMemory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ActionParser actionParser(ObjectMapper objectMapper) {
        return new ActionParser(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(TraceRecorder.class)
    public AgentLoop agentLoopWithTrace(StateReducer stateReducer,
                                        ContextAssembler contextAssembler,
                                        LlmRouter llmRouter,
                                        MultimodalRouter multimodalRouter,
                                        TraceRecorder traceRecorder,
                                        SessionManager sessionManager,
                                        ConversationViewService conversationViewService,
                                        ActionParser actionParser,
                                        AgentToolProvider agentToolProvider,
                                        AgentConfigProperties config,
                                        @Autowired(required = false) WorkingMemory workingMemory,
                                        @Autowired(required = false) EpisodicMemory episodicMemory,
                                        @Autowired(required = false) ConversationHistoryStore conversationHistoryStore,
                                        @Autowired(required = false) SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                                        @Autowired(required = false) KnowledgeBaseRepository knowledgeBaseRepository) {
        log.info("Agent 引擎初始化完成（带追踪，记忆系统{}，情景记忆{}）",
                workingMemory != null ? "已启用" : "未启用",
                episodicMemory != null ? "已启用" : "未启用");
        return new AgentLoop(stateReducer, contextAssembler, llmRouter, multimodalRouter,
                traceRecorder, sessionManager, conversationViewService, actionParser, agentToolProvider,
                config, workingMemory, conversationHistoryStore, sessionKnowledgeBaseRepository, knowledgeBaseRepository);
    }

    @Bean
    @ConditionalOnMissingBean(AgentLoop.class)
    public AgentLoop agentLoopWithoutTrace(StateReducer stateReducer,
                                           ContextAssembler contextAssembler,
                                           LlmRouter llmRouter,
                                           MultimodalRouter multimodalRouter,
                                           SessionManager sessionManager,
                                           ConversationViewService conversationViewService,
                                           ActionParser actionParser,
                                           AgentToolProvider agentToolProvider,
                                           AgentConfigProperties config,
                                           @org.springframework.beans.factory.annotation.Autowired(required = false) WorkingMemory workingMemory,
                                           @org.springframework.beans.factory.annotation.Autowired(required = false) com.lifepilot.memory.episodic.EpisodicMemory episodicMemory,
                                           @org.springframework.beans.factory.annotation.Autowired(required = false) ConversationHistoryStore conversationHistoryStore,
                                           @org.springframework.beans.factory.annotation.Autowired(required = false) com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                                           @org.springframework.beans.factory.annotation.Autowired(required = false) com.lifepilot.knowledge.repository.KnowledgeBaseRepository knowledgeBaseRepository) {
        log.info("Agent 引擎初始化完成（无追踪，记忆系统{}，情景记忆{}）",
                workingMemory != null ? "已启用" : "未启用",
                episodicMemory != null ? "已启用" : "未启用");
        return new AgentLoop(stateReducer, contextAssembler, llmRouter, multimodalRouter,
                null, sessionManager, conversationViewService, actionParser, agentToolProvider,
                config, workingMemory, conversationHistoryStore, sessionKnowledgeBaseRepository, knowledgeBaseRepository);
    }
}
