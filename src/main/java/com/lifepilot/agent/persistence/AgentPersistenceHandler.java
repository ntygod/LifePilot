package com.lifepilot.agent.persistence;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.media.MediaDataExtractor;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.conversation.ConversationViewService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.llm.multimodal.MediaContent;
import com.lifepilot.memory.retrieval.InjectionRecordRepository;
import com.lifepilot.memory.semantic.RealtimeExtractor;
import com.lifepilot.memory.working.ConversationSlot;
import com.lifepilot.memory.working.WorkingMemory;
import com.lifepilot.memory.experience.EffectivenessTracker;
import com.lifepilot.memory.experience.ContrastiveLearner;
import com.lifepilot.memory.experience.ExperienceSummarizer;
import com.lifepilot.memory.experience.SubtaskReflector;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 持久化处理器 — 聚合 L1 工作记忆读写、对话历史持久化、附件持久化、异步后处理。
 *
 * <p>从 ReactAgentLoop 提取的所有持久化相关方法，集中管理数据写入逻辑，
 * 降低 ReactAgentLoop 的职责复杂度。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
public class AgentPersistenceHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentPersistenceHandler.class);

    // ===== 核心依赖 =====
    private final AgentConfigProperties config;
    private final SessionManager sessionManager;

    // ===== 可选依赖（@Nullable） =====
    @Nullable private final WorkingMemory workingMemory;
    @Nullable private final ConversationHistoryStore conversationHistoryStore;
    @Nullable private final ConversationViewService conversationViewService;
    @Nullable private final RealtimeExtractor realtimeExtractor;
    @Nullable private final InjectionRecordRepository injectionRecordRepository;
    @Nullable private final AttachmentRepository attachmentRepository;
    @Nullable private final ExperienceSummarizer experienceSummarizer;
    @Nullable private final EffectivenessTracker effectivenessTracker;
    @Nullable private final ContrastiveLearner contrastiveLearner;
    @Nullable private final SubtaskReflector subtaskReflector;

    public AgentPersistenceHandler(
            AgentConfigProperties config,
            SessionManager sessionManager,
            @Nullable WorkingMemory workingMemory,
            @Nullable ConversationHistoryStore conversationHistoryStore,
            @Nullable ConversationViewService conversationViewService,
            @Nullable RealtimeExtractor realtimeExtractor,
            @Nullable InjectionRecordRepository injectionRecordRepository,
            @Nullable AttachmentRepository attachmentRepository,
            @Nullable ExperienceSummarizer experienceSummarizer,
            @Nullable EffectivenessTracker effectivenessTracker,
            @Nullable ContrastiveLearner contrastiveLearner,
            @Nullable SubtaskReflector subtaskReflector) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.workingMemory = workingMemory;
        this.conversationHistoryStore = conversationHistoryStore;
        this.conversationViewService = conversationViewService;
        this.realtimeExtractor = realtimeExtractor;
        this.injectionRecordRepository = injectionRecordRepository;
        this.attachmentRepository = attachmentRepository;
        this.experienceSummarizer = experienceSummarizer;
        this.effectivenessTracker = effectivenessTracker;
        this.contrastiveLearner = contrastiveLearner;
        this.subtaskReflector = subtaskReflector;
    }

    // ===== L1 工作记忆读写 =====

    /**
     * 将用户消息写入 L1 工作记忆。
     *
     * <p>当请求包含媒体内容时，在消息末尾附加元信息标注（MIME 类型 + 文件名），
     * 不存储原始二进制数据到 WorkingMemory。</p>
     *
     * @param state         当前 Agent 状态
     * @param mediaContents 请求关联的媒体内容列表（可空）
     */
    public void writeUserMessageToL1(ReactAgentState state,
                                     @Nullable List<MediaContent> mediaContents) {
        if (workingMemory == null || state.goal() == null || state.goal().isBlank()) return;
        try {
            String content = state.goal();

            // 附加媒体元信息标注（不含二进制数据）
            if (mediaContents != null && !mediaContents.isEmpty()) {
                String annotation = mediaContents.stream()
                        .map(mc -> mc.mimeType() + ": " + (mc.fileName() != null ? mc.fileName() : "unnamed"))
                        .collect(Collectors.joining(", "));
                content = content + "\n[附件: " + annotation + "]";
            }

            int tokens = estimateTextTokens(content);
            var slot = ConversationSlot.userMessage(content, tokens);
            workingMemory.append(state.sessionId(), slot);
        } catch (Exception e) {
            log.warn("用户消息写入 L1 失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
        }
    }

    /** 将 AI 响应写入 L1 工作记忆。 */
    public void writeAssistantMessageToL1(ReactAgentState state) {
        if (workingMemory == null) return;
        String response = state.finalOutput();
        if (response == null || response.isBlank()) return;
        try {
            int tokens = estimateTextTokens(response);
            var slot = ConversationSlot.assistantMessage(response, tokens);
            workingMemory.append(state.sessionId(), slot);
        } catch (Exception e) {
            log.warn("AI 响应写入 L1 失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
        }
    }

    /** 当 L1 为空时，从 ConversationViewService 回灌对话历史。 */
    public void hydrateWorkingMemoryFromConversationView(String sessionId) {
        if (workingMemory == null || conversationViewService == null
                || sessionId == null || sessionId.isBlank()) return;
        try {
            var existingSlots = workingMemory.getContext(sessionId);
            if (existingSlots != null && !existingSlots.isEmpty()) return;
            var turns = conversationViewService.getRecentTurns(
                    sessionId, config.getSession().getMaxRecentTurns());
            for (var turn : turns) {
                if (turn.content() != null && !turn.content().isBlank()) {
                    if ("user".equalsIgnoreCase(turn.role())) {
                        workingMemory.append(sessionId,
                                ConversationSlot.userMessage(
                                        turn.content(), estimateTextTokens(turn.content())));
                    } else if ("assistant".equalsIgnoreCase(turn.role())) {
                        workingMemory.append(sessionId,
                                ConversationSlot.assistantMessage(
                                        turn.content(), estimateTextTokens(turn.content())));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("L1 对话历史回灌失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    // ===== 对话历史持久化 =====

    /** 同步写入用户消息到 chat_messages。 */
    public void persistUserMessage(ReactAgentState state) {
        if (conversationHistoryStore == null
                || state.goal() == null || state.goal().isBlank()) return;
        try {
            conversationHistoryStore.appendUserMessage(
                    state.sessionId(), state.goal(), state.traceId());
        } catch (Exception e) {
            log.warn("用户消息同步写入失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
        }
    }

    /**
     * 同步写入用户消息到 chat_messages，返回 messageId。
     *
     * <p>流式模式使用此方法，需要返回 userMessageId 用于 TRACE_START 事件。</p>
     *
     * @param state 当前 Agent 状态
     * @return 用户消息 ID，写入失败时返回 null
     */
    @Nullable
    public String persistUserMessageReturningId(ReactAgentState state) {
        if (conversationHistoryStore == null
                || state.goal() == null || state.goal().isBlank()) return null;
        try {
            return conversationHistoryStore.appendUserMessage(
                    state.sessionId(), state.goal(), state.traceId());
        } catch (Exception e) {
            log.warn("用户消息同步写入失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return null;
        }
    }

    /** 同步写入助手消息到 chat_messages。 */
    @Nullable
    public String persistAssistantMessage(ReactAgentState state) {
        if (conversationHistoryStore == null) return null;
        String output = state.finalOutput();
        if (output == null || output.isBlank()) return null;
        try {
            return conversationHistoryStore.appendAssistantMessage(
                    state.sessionId(), output, state.reasoningSummary(),
                    state.traceId(), null);
        } catch (Exception e) {
            log.warn("助手消息同步写入失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return null;
        }
    }

    /**
     * 同步写入助手消息到 chat_messages（含 A2UI JSON）。
     *
     * <p>流式模式使用此方法，需要额外传入 A2UI JSON 和最终内容。</p>
     *
     * @param state             当前 Agent 状态
     * @param finalContent      最终文本内容
     * @param reasoningSummary  推理概要
     * @param a2uiJson          A2UI 组件树 JSON（可空）
     * @return 助手消息 ID，写入失败时返回 null
     */
    @Nullable
    public String persistAssistantMessageWithA2ui(ReactAgentState state,
                                                   @Nullable String finalContent,
                                                   @Nullable String reasoningSummary,
                                                   @Nullable String a2uiJson) {
        if (conversationHistoryStore == null) return null;
        if ((finalContent == null || finalContent.isBlank()) && a2uiJson == null) return null;
        try {
            return conversationHistoryStore.appendAssistantMessage(
                    state.sessionId(),
                    finalContent != null ? finalContent : "",
                    reasoningSummary, state.traceId(), a2uiJson);
        } catch (Exception e) {
            log.warn("助手消息同步写入失败: sessionId={}, error={}",
                    state.sessionId(), e.getMessage());
            return null;
        }
    }

    /**
     * 持久化注入记录。
     *
     * @param messageId 助手消息 ID
     * @param sessionId 会话 ID
     * @param loopContext 循环上下文（从中读取 injectedEntityIds）
     */
    public void persistInjectionRecord(@Nullable String messageId,
                                       @Nullable String sessionId,
                                       @Nullable AgentLoopContext loopContext) {
        List<String> entityIds = loopContext != null
                ? loopContext.getInjectedEntityIds() : List.of();
        if (injectionRecordRepository == null || entityIds.isEmpty()
                || messageId == null || messageId.isBlank()) return;
        try {
            injectionRecordRepository.save(messageId, sessionId, entityIds);
            log.debug("注入记录已持久化: messageId={}, entityCount={}", messageId, entityIds.size());
        } catch (Exception e) {
            log.warn("注入记录持久化失败: messageId={}, error={}", messageId, e.getMessage());
        }
    }

    /**
     * 持久化工具产生的媒体附件到 message_attachments 表。
     *
     * <p>将本轮 ReAct 循环中通过 SSE MEDIA 事件发送的媒体数据（截图等）
     * 写入附件表，确保页面刷新后仍能加载。</p>
     *
     * @param assistantMessageId 助手消息 ID
     * @param sessionId          会话 ID
     * @param toolMediaItems     工具产生的媒体数据列表
     */
    public void persistToolMediaAttachments(@Nullable String assistantMessageId,
                                            @Nullable String sessionId,
                                            List<MediaDataExtractor.MediaItem> toolMediaItems) {
        if (attachmentRepository == null || assistantMessageId == null
                || toolMediaItems.isEmpty()) {
            return;
        }
        for (var mediaItem : toolMediaItems) {
            try {
                String ext = guessExtension(mediaItem.mediaType());
                String fileName = mediaItem.fieldName() + "." + ext;
                String dataUri = "data:" + mediaItem.mediaType() + ";base64," + mediaItem.data();
                long sizeBytes = Math.round(mediaItem.data().length() * 0.75);
                attachmentRepository.save(assistantMessageId, sessionId,
                        fileName, "", sizeBytes, mediaItem.mediaType(), dataUri);
            } catch (Exception e) {
                log.warn("工具媒体附件持久化失败: field={}, error={}",
                        mediaItem.fieldName(), e.getMessage());
            }
        }
        log.debug("工具媒体附件持久化完成: sessionId={}, count={}",
                sessionId, toolMediaItems.size());
    }

    /**
     * 持久化用户上传的媒体附件到 message_attachments 表。
     *
     * @param assistantMessageId 助手消息 ID
     * @param sessionId          会话 ID
     * @param mediaContents      用户上传的媒体内容列表
     */
    public void persistUserMediaAttachments(@Nullable String assistantMessageId,
                                            @Nullable String sessionId,
                                            @Nullable List<MediaContent> mediaContents) {
        if (attachmentRepository == null || assistantMessageId == null
                || mediaContents == null || mediaContents.isEmpty()) {
            return;
        }
        for (var mc : mediaContents) {
            try {
                String fileName = mc.fileName() != null ? mc.fileName()
                        : "media-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                          + "." + guessExtension(mc.mimeType());
                String dataUri = "data:" + mc.mimeType() + ";base64,"
                        + java.util.Base64.getEncoder().encodeToString(mc.data());
                attachmentRepository.save(assistantMessageId, sessionId,
                        fileName, "", mc.sizeBytes(), mc.mimeType(), dataUri);
            } catch (Exception e) {
                log.warn("媒体附件持久化失败: sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
        }
        log.debug("媒体附件持久化完成: sessionId={}, count={}", sessionId, mediaContents.size());
    }

    // ===== 异步后处理 =====

    /** 异步后处理 — 会话快照持久化 + AUDN 实体提取 + 经验提炼 + 效果评估。 */
    public void asyncPostProcess(ReactAgentState finalState) {
        Thread.startVirtualThread(() -> {
            try {
                sessionManager.saveSession(finalState);
            } catch (Exception e) {
                log.warn("会话快照持久化失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
            try {
                if (realtimeExtractor != null && finalState.finalOutput() != null) {
                    realtimeExtractor.extractAsync(
                            finalState.sessionId(),
                            finalState.goal(),
                            finalState.finalOutput());
                }
            } catch (Exception e) {
                log.warn("AUDN 实时实体提取失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
            // 经验提炼
            TemporalEntity newExperience = null;
            try {
                if (experienceSummarizer != null) {
                    newExperience = experienceSummarizer.summarize(finalState);
                }
            } catch (Exception e) {
                log.warn("经验提炼失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
            // 效果评估
            try {
                if (effectivenessTracker != null) {
                    effectivenessTracker.evaluate(finalState, finalState.traceId());
                }
            } catch (Exception e) {
                log.warn("效果评估失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
            // 对比学习
            try {
                if (contrastiveLearner != null && newExperience != null) {
                    contrastiveLearner.learn(newExperience);
                }
            } catch (Exception e) {
                log.warn("对比学习失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
            // 子任务反思
            try {
                if (subtaskReflector != null) {
                    subtaskReflector.reflect(finalState);
                }
            } catch (Exception e) {
                log.warn("子任务反思失败: sessionId={}, error={}",
                        finalState.sessionId(), e.getMessage());
            }
        });
    }

    /** 持久化流式系统错误消息到对话历史和 L1。 */
    public void persistStreamingSystemError(@Nullable String sessionId,
                                            @Nullable String traceId,
                                            @Nullable Exception e) {
        try {
            if (sessionId == null || sessionId.isBlank()) return;
            String detail = e != null ? e.getMessage() : "unknown";
            String content = "系统提示：模型服务暂时不可用，请稍后重试。\n（错误信息）" + detail;
            if (conversationHistoryStore != null) {
                conversationHistoryStore.appendSystemMessage(sessionId, content, traceId);
            }
            if (workingMemory != null) {
                int tokens = estimateTextTokens(content);
                workingMemory.append(sessionId,
                        ConversationSlot.systemMessage(content, tokens));
            }
        } catch (Exception ignore) { /* 不影响主流程 */ }
    }

    // ===== 工具方法 =====

    /** 估算文本 Token 数（CJK 字符按 1:1，其他按 4:1）。 */
    static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long cjkChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - cjkChars;
        return Math.max(1, (int) (cjkChars + otherChars / 4));
    }

    /** 根据 MIME 类型猜测文件扩展名。 */
    static String guessExtension(@Nullable String mimeType) {
        if (mimeType == null) return "bin";
        return switch (mimeType) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            case "application/pdf" -> "pdf";
            default -> "bin";
        };
    }
}
