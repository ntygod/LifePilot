package com.lifepilot.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.ActionParser;
import com.lifepilot.agent.AgentLoop;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.StateReducer;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.context.DefaultMemoryRetrievalStrategy;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.proactive.channel.PassiveNotificationQueue;
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
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.working.TokenBudgetAllocator;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.llm.config.LlmAutoConfiguration;
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
@AutoConfiguration(after = {ToolAutoConfiguration.class, LlmAutoConfiguration.class})
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
                                             PromptRegistry promptRegistry,
                                             @Autowired(required = false) HybridRetriever hybridRetriever,
                                             @Autowired(required = false) WorkingMemory workingMemory,
                                             @Autowired(required = false) TokenBudgetAllocator tokenBudgetAllocator,
                                             @Autowired(required = false) DataRedactor dataRedactor,
                                             @Autowired(required = false) DocumentRetriever documentRetriever,
                                             @Autowired(required = false) SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                                             @Autowired(required = false) DocumentRepository documentRepository,
                                             @Autowired(required = false) EpisodicMemory episodicMemory,
                                             @Autowired(required = false) SemanticMemory semanticMemory,
                                             @Autowired(required = false) PassiveNotificationQueue passiveNotificationQueue) {
        if (hybridRetriever != null && workingMemory != null && tokenBudgetAllocator != null) {
            log.info("Agent 引擎: 注册完整版 ContextAssembler（记忆系统已就绪，L2 情景记忆{}，L3 语义记忆{}）",
                    episodicMemory != null ? "已启用" : "未启用",
                    semanticMemory != null ? "已启用" : "未启用");
            var strategy = new DefaultMemoryRetrievalStrategy();
            return new ContextAssembler(config, hybridRetriever,
                    workingMemory, tokenBudgetAllocator, strategy, dataRedactor,
                    documentRetriever, sessionKnowledgeBaseRepository, documentRepository,
                    episodicMemory, semanticMemory, passiveNotificationQueue, promptRegistry);
        }
        log.warn("Agent 引擎: 注册基础版 ContextAssembler（记忆系统部分或全部不可用，记忆检索功能已降级）");
        return new ContextAssembler(config, promptRegistry);
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
    @ConditionalOnBean(EpisodicMemory.class)
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
    public MediaDataExtractor mediaDataExtractor(ObjectMapper objectMapper) {
        return new MediaDataExtractor(objectMapper);
    }

    /**
     * 统一的 AgentLoop bean 创建方法。
     *
     * <p>通过 {@code @Autowired(required = false)} 注入可选依赖，
     * 在依赖注入阶段自动解析。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentLoop agentLoop(StateReducer stateReducer,
                               ContextAssembler contextAssembler,
                               LlmRouter llmRouter,
                               @Autowired(required = false) MultimodalRouter multimodalRouter,
                               ObjectMapper objectMapper,
                               SessionManager sessionManager,
                               @Autowired(required = false) ConversationViewService conversationViewService,
                               ActionParser actionParser,
                               AgentToolProvider agentToolProvider,
                               AgentConfigProperties config,
                               @Autowired(required = false) TraceRecorder traceRecorder,
                               @Autowired(required = false) WorkingMemory workingMemory,
                               @Autowired(required = false) EpisodicMemory episodicMemory,
                               @Autowired(required = false) ConversationHistoryStore conversationHistoryStore,
                               @Autowired(required = false) SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                               @Autowired(required = false) KnowledgeBaseRepository knowledgeBaseRepository,
                               @Autowired(required = false) RealtimeExtractor realtimeExtractor,
                               PromptRegistry promptRegistry,
                               @Autowired(required = false) MediaDataExtractor mediaDataExtractor) {
        log.info("Agent 引擎初始化完成（追踪{}，记忆系统{}，情景记忆{}，实时提取{}，多模态{}）",
                traceRecorder != null ? "已启用" : "未启用",
                workingMemory != null ? "已启用" : "未启用",
                episodicMemory != null ? "已启用" : "未启用",
                realtimeExtractor != null ? "已启用" : "未启用",
                multimodalRouter != null ? "已启用" : "未启用（纯文本模式）");
        return new AgentLoop(stateReducer, contextAssembler, llmRouter, multimodalRouter,
                traceRecorder, objectMapper, sessionManager, conversationViewService, actionParser, agentToolProvider,
                config, workingMemory, conversationHistoryStore, sessionKnowledgeBaseRepository,
                knowledgeBaseRepository, realtimeExtractor, promptRegistry, mediaDataExtractor);
    }
}
