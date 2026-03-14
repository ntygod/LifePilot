package com.lifepilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.a2ui.A2uiComponentCatalog;
import com.lifepilot.interaction.web.a2ui.A2uiComponentValidator;
import com.lifepilot.interaction.web.a2ui.A2uiPayloadSupport;
import com.lifepilot.interaction.web.a2ui.StreamingA2uiParser;
import com.lifepilot.interaction.web.config.A2uiProperties;
import com.lifepilot.interaction.web.model.A2uiComponentTree;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.repository.KnowledgeBaseRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.multimodal.MultimodalRouter;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.observability.trace.TraceContext;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * ReAct Agent 循环 — 替代原六阶段状态机 AgentLoop。
 *
 * <p>核心循环：Thought → Action → Observation，直到 LLM 返回纯文本（无 tool call）
 * 或预算耗尽 / 取消信号触发。</p>
 *
 * <p>通过 Spring AI 原生 function calling（ChatClient + ToolCallback）实现工具调用，
 * 移除了原架构的 ActionParser 和显式阶段枚举。</p>
 *
 * @author zsg
 * @since 2026-03-14
 */
public class ReactAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(ReactAgentLoop.class);

    // ===== 核心依赖（必需） =====
    private final ContextAssembler contextAssembler;
    private final LlmRouter llmRouter;
    private final TraceRecorder traceRecorder;
    private final ObjectMapper objectMapper;
    private final SessionManager sessionManager;
    private final AgentToolProvider agentToolProvider;
    private final AgentConfigProperties config;
    private final PromptRegistry promptRegistry;

    // ===== 可选依赖（@Nullable） =====
    @Nullable private final MultimodalRouter multimodalRouter;
    @Nullable private final MediaDataExtractor mediaDataExtractor;
    @Nullable private final WorkingMemory workingMemory;
    @Nullable private final ConversationHistoryStore conversationHistoryStore;
    @Nullable private final ConversationViewService conversationViewService;
    @Nullable private final RealtimeExtractor realtimeExtractor;
    @Nullable private final InjectionRecordRepository injectionRecordRepository;
    @Nullable private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable private final KnowledgeBaseRepository knowledgeBaseRepository;
    @Nullable private final A2uiProperties a2uiProperties;

    // ===== 运行时状态（volatile） =====
    private volatile A2uiComponentTree lastCollectedA2uiTree;
    private volatile List<String> lastInjectedEntityIds = List.of();
    private volatile CancellationToken cancellationToken;

    public ReactAgentLoop(
            ContextAssembler contextAssembler,
            LlmRouter llmRouter,
            TraceRecorder traceRecorder,
            ObjectMapper objectMapper,
            SessionManager sessionManager,
            AgentToolProvider agentToolProvider,
            AgentConfigProperties config,
            PromptRegistry promptRegistry,
            @Nullable MultimodalRouter multimodalRouter,
            @Nullable MediaDataExtractor mediaDataExtractor,
            @Nullable WorkingMemory workingMemory,
            @Nullable ConversationHistoryStore conversationHistoryStore,
            @Nullable ConversationViewService conversationViewService,
            @Nullable RealtimeExtractor realtimeExtractor,
            @Nullable InjectionRecordRepository injectionRecordRepository,
            @Nullable SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
            @Nullable KnowledgeBaseRepository knowledgeBaseRepository,
            @Nullable A2uiProperties a2uiProperties) {
        this.contextAssembler = contextAssembler;
        this.llmRouter = llmRouter;
        this.traceRecorder = traceRecorder;
        this.objectMapper = objectMapper;
        this.sessionManager = sessionManager;
        this.agentToolProvider = agentToolProvider;
        this.config = config;
        this.promptRegistry = promptRegistry;
        this.multimodalRouter = multimodalRouter;
        this.mediaDataExtractor = mediaDataExtractor;
        this.workingMemory = workingMemory;
        this.conversationHistoryStore = conversationHistoryStore;
        this.conversationViewService = conversationViewService;
        this.realtimeExtractor = realtimeExtractor;
        this.injectionRecordRepository = injectionRecordRepository;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.a2uiProperties = a2uiProperties;
    }

    /**
     * 迭代回调 — 抽象 LLM 调用方式（同步 / 流式）。
     */
    @FunctionalInterface
    interface IterationCallback {
        /**
         * 调用 LLM 并返回响应。
         *
         * @param request       原始请求
         * @param messages      Spring AI 消息列表
         * @param toolCallbacks 工具回调列表
         * @param traceContext  追踪上下文
         * @return LLM 响应
         */
        ChatResponse callLlm(AgentRequest request,
                              List<Message> messages,
                              List<ToolCallback> toolCallbacks,
                              @Nullable TraceContext traceContext);
    }
}
