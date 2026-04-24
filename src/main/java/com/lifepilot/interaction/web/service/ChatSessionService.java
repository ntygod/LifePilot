package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.context.TranscriptCompactionBoundaryResolver;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.conversation.transcript.TranscriptEntryType;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.interaction.web.a2ui.A2uiPayloadSupport;
import com.lifepilot.interaction.web.model.*;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.project.service.ProjectService;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 聊天会话管理服务 — 负责会话的创建、查询、更新与删除等生命周期管理。
 *
 * @author zsg
 * @since 2026-03-01
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final ChatSessionRepository sessionRepository;
    private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    private final SessionDatastoreRepository sessionDatastoreRepository;
    private final AttachmentRepository attachmentRepository;
    private final ObjectMapper objectMapper;
    private final SessionTranscriptRepository transcriptRepository;
    @Nullable
    private final SessionStoreRepository sessionStoreRepository;
    @Nullable
    private final AgentConfigProperties agentConfig;
    @Nullable
    private final TranscriptCompactionBoundaryResolver compactionBoundaryResolver;
    @Nullable
    private final GenerationRouter generationRouter;
    @Nullable
    private final ChatTurnService chatTurnService;
    /**
     * 项目服务 —— 可选依赖。创建项目会话时用于查询项目默认 KB 并自动关联到 session，
     * 让项目内的对话自动带上项目知识库的 RAG 检索。KB 未启用或极简部署下可为 null，
     * 此时退化为不做自动关联。
     */
    @Nullable
    private final ProjectService projectService;

    public ChatSessionService(ChatSessionRepository sessionRepository,
                              SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                              SessionDatastoreRepository sessionDatastoreRepository,
                              AttachmentRepository attachmentRepository,
                              ObjectMapper objectMapper,
                              SessionTranscriptRepository transcriptRepository,
                              @Nullable SessionStoreRepository sessionStoreRepository,
                              @Nullable AgentConfigProperties agentConfig,
                              @Nullable TranscriptCompactionBoundaryResolver compactionBoundaryResolver,
                              @Nullable GenerationRouter generationRouter,
                              @Nullable ChatTurnService chatTurnService,
                              @Nullable ProjectService projectService) {
        this.sessionRepository = sessionRepository;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.sessionDatastoreRepository = sessionDatastoreRepository;
        this.attachmentRepository = attachmentRepository;
        this.objectMapper = objectMapper;
        this.transcriptRepository = transcriptRepository;
        this.sessionStoreRepository = sessionStoreRepository;
        this.agentConfig = agentConfig;
        this.compactionBoundaryResolver = compactionBoundaryResolver;
        this.generationRouter = generationRouter;
        this.chatTurnService = chatTurnService;
        this.projectService = projectService;
    }

    @Transactional
    public ChatSession createSession(String title) {
        return createSession(title, null);
    }

    /**
     * 创建会话并指定归属项目。
     *
     * <p>若 {@code projectId} 非空且 {@link ProjectService} 已注入，会把项目默认 KB
     * 自动关联到新会话的 session_knowledge_bases 表——让"在项目里开的对话"默认
     * 继承项目知识库的 RAG 检索，避免用户每次还要手动在 ChatInput 里选 KB。</p>
     *
     * <p>KB 查询/写入失败不抛出，只记警告 —— 会话创建作为主路径不应被 KB 偶发故障阻断。</p>
     *
     * @param title     会话标题（可选）
     * @param projectId 归属项目 ID（可选，NULL = 归属主账户）
     * @return 新创建的会话实例
     */
    @Transactional
    public ChatSession createSession(String title, @Nullable String projectId) {
        ChatSession session = ChatSession.create(title, projectId);
        sessionRepository.save(session);
        log.info("创建会话: id={}, title={}, projectId={}", session.id(), session.title(), projectId);
        inheritProjectKnowledgeBases(session.id(), projectId);
        return session;
    }

    /**
     * 把项目默认 KB 关联到会话。projectId 为空或 ProjectService 未注入时静默跳过。
     */
    private void inheritProjectKnowledgeBases(String sessionId, @Nullable String projectId) {
        if (projectId == null || projectId.isBlank() || projectService == null) {
            return;
        }
        try {
            List<String> kbIds = projectService.findKnowledgeBaseIds(projectId);
            if (kbIds.isEmpty()) {
                return;
            }
            for (String kbId : kbIds) {
                sessionKnowledgeBaseRepository.addAssociation(sessionId, kbId);
            }
            log.info("项目会话自动关联项目默认 KB: sessionId={}, projectId={}, kbIds={}",
                    sessionId, projectId, kbIds);
        } catch (RuntimeException e) {
            log.warn("项目会话关联项目默认 KB 失败（会话仍正常创建）: sessionId={}, projectId={}, error={}",
                    sessionId, projectId, e.getMessage());
        }
    }

    public List<SessionInfo> listSessions() {
        return sessionRepository.findAll().stream()
                .map(this::toSessionInfo)
                .toList();
    }

    public List<SessionInfo> listSessions(String q, Boolean pinned, Boolean archived,
                                          String timeRange, String sortBy, String order) {
        return listSessions(q, pinned, archived, timeRange, sortBy, order, null);
    }

    /**
     * 按项目维度获取会话列表。
     *
     * <p>projectId 语义：</p>
     * <ul>
     *   <li>{@code null}（默认）—— 只返回主账户对话（project_id IS NULL）</li>
     *   <li>非空 —— 只返回归属该项目的对话</li>
     * </ul>
     *
     * @param projectId 归属项目 ID（可选）
     */
    public List<SessionInfo> listSessions(String q, Boolean pinned, Boolean archived,
                                          String timeRange, String sortBy, String order,
                                          @Nullable String projectId) {
        SessionStoreRepository.ProjectScope projectScope = projectId == null || projectId.isBlank()
                ? SessionStoreRepository.ProjectScope.mainAccount()
                : SessionStoreRepository.ProjectScope.ofProject(projectId);
        return sessionRepository
                .findByConditions(q, pinned, archived, timeRange, sortBy, order, projectScope)
                .stream()
                .map(this::toSessionInfo)
                .toList();
    }

    public Optional<ChatSession> getSession(String id) {
        return isWebSessionId(id) ? sessionRepository.findById(id) : Optional.empty();
    }

    @Transactional
    public ChatSession updateTitle(String id, String title) {
        ChatSession session = requireWebSession(id);
        sessionRepository.updateTitle(id, title);
        log.info("更新会话标题: id={}, title={}", id, title);
        return sessionRepository.findById(id).orElse(session);
    }

    @Transactional
    public ChatSession updatePinned(String id, boolean isPinned) {
        ChatSession session = requireWebSession(id);
        sessionRepository.updatePinned(id, isPinned);
        log.info("更新会话置顶状态: id={}, pinned={}", id, isPinned);
        return sessionRepository.findById(id).orElse(session);
    }

    @Transactional
    public SessionInfo updateSession(String id, String title, Boolean pinned, Boolean archived) {
        ChatSession session = requireWebSession(id);

        if (title == null && pinned == null && archived == null) {
            log.debug("跳过空会话更新: id={}", id);
            return toSessionInfo(session);
        }

        sessionRepository.updateFields(id, title, pinned, archived);
        log.info("更新会话: id={}, title={}, pinned={}, archived={}", id, title, pinned, archived);

        ChatSession updatedSession = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("会话更新后消失: id=" + id));
        return toSessionInfo(updatedSession);
    }

    @Transactional
    public void deleteSession(String id) {
        requireWebSession(id);
        sessionRepository.deleteById(id);
        log.info("删除会话: id={}", id);
    }

    @Transactional
    public void clearSessionMessages(String id) {
        requireWebSession(id);
        transcriptRepository.deleteBySessionId(id);
        attachmentRepository.deleteBySessionId(id);
        sessionRepository.clearMessages(id);
        log.info("清空会话消息: id={}", id);
    }

    @Transactional
    public void incrementMessageCount(String id) {
        sessionRepository.incrementMessageCount(id);
    }

    public List<MessageInfo> getSessionMessages(String id) {
        requireWebSession(id);
        return withAttachments(loadTranscriptMessages(id));
    }

    @Transactional
    public int batchUpdateSessions(String action, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            log.warn("跳过批量会话更新: ID 列表为空");
            return 0;
        }

        return switch (action) {
            case "pin" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, true, null);
                log.info("批量置顶会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "unpin" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, false, null);
                log.info("批量取消置顶会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "archive" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, null, true);
                log.info("批量归档会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "unarchive" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, null, false);
                log.info("批量取消归档会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "delete" -> {
                int count = sessionRepository.batchDelete(sessionIds);
                log.info("批量删除会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            default -> throw new IllegalArgumentException("不支持的批量操作: " + action);
        };
    }

    public SessionDetailInfo getSessionDetail(String id) {
        ChatSession session = requireWebSession(id);
        List<String> knowledgeBaseIds = sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(id);
        List<String> datastoreIds = sessionDatastoreRepository.findDatastoreIdsBySessionId(id);
        Map<String, Object> sessionConfig = sessionRepository.getConfig(id);
        SessionCompactionStatusInfo compactionStatus = buildCompactionStatus(id, sessionConfig);

        return new SessionDetailInfo(
                session.id(),
                session.title(),
                session.createdAt(),
                session.updatedAt(),
                session.isPinned(),
                session.archived(),
                SessionConfigKeys.resolvePreferredProviderId(sessionConfig),
                SessionConfigKeys.getDouble(sessionConfig, SessionConfigKeys.TEMPERATURE),
                SessionConfigKeys.getInteger(sessionConfig, SessionConfigKeys.MAX_STEPS),
                SessionConfigKeys.getInteger(sessionConfig, SessionConfigKeys.MAX_DURATION_SECONDS),
                knowledgeBaseIds,
                datastoreIds,
                session.messageCount(),
                0L,
                session.summary(),
                compactionStatus
        );
    }

    @Transactional
    public SessionInfo forkSession(String originalSessionId, String fromEntryId, String newTitle) {
        ChatSession originalSession = requireSession(originalSessionId);
        List<SessionTranscriptRepository.TranscriptMessageViewRow> transcriptMessages =
                transcriptRepository.findUserConversationRowsBySessionId(originalSessionId);
        return forkFromTranscript(originalSession, transcriptMessages, fromEntryId, newTitle);
    }

    @Transactional
    public void updateSessionConfig(String id, SessionConfigRequest request) {
        requireWebSession(id);

        Map<String, Object> config = new HashMap<>();
        config.put(SessionConfigKeys.PREFERRED_PROVIDER, SessionConfigKeys.normalizeString(request.preferredProviderId()));
        config.put(SessionConfigKeys.LEGACY_MODEL_ID, null);
        config.put(SessionConfigKeys.TEMPERATURE, request.temperature());
        config.put(SessionConfigKeys.MAX_STEPS, request.maxSteps());
        config.put(SessionConfigKeys.MAX_DURATION_SECONDS, request.maxDurationSeconds());

        sessionRepository.updateConfig(id, config);
        log.info("更新会话配置: sessionId={}, config={}", id, config);

        if (request.knowledgeBaseIds() != null) {
            sessionKnowledgeBaseRepository.setAssociations(id, request.knowledgeBaseIds());
            log.info("更新会话知识库关联: sessionId={}, knowledgeBaseIds={}",
                    id, request.knowledgeBaseIds());
        }
        if (request.datastoreIds() != null) {
            sessionDatastoreRepository.setAssociations(id, request.datastoreIds());
            log.info("更新会话 datastore 关联: sessionId={}, datastoreIds={}",
                    id, request.datastoreIds());
        }
    }

    private List<MessageInfo> loadTranscriptMessages(String sessionId) {
        Map<String, ChatTurnRecord> turnsByTurnId = new HashMap<>();
        if (chatTurnService != null) {
            for (ChatTurnRecord turn : chatTurnService.findBySessionId(sessionId)) {
                turnsByTurnId.put(turn.turnId(), turn);
            }
        }
        return transcriptRepository.findUserConversationRowsBySessionId(sessionId).stream()
                .map(row -> toMessageInfo(row, turnsByTurnId))
                .toList();
    }

    private MessageInfo toMessageInfo(SessionTranscriptRepository.TranscriptMessageViewRow row,
                                      Map<String, ChatTurnRecord> turnsByTurnId) {
        var tree = A2uiPayloadSupport.deserializeStoredTree(row.a2uiComponentsJson(), objectMapper);
        ChatTurnRecord turn = row.turnId() != null ? turnsByTurnId.get(row.turnId()) : null;
        var rowCompletionMode = parseCompletionMode(row.completionMode());
        boolean currentAttemptMessage = isCurrentAttemptMessage(row, turn);
        var effectiveCompletionMode = resolveMessageCompletionMode(turn, rowCompletionMode, currentAttemptMessage);
        ChatTurnStatus effectiveTurnStatus = resolveMessageTurnStatus(turn, rowCompletionMode, currentAttemptMessage);
        return new MessageInfo(
                row.entryId(),
                row.turnId(),
                row.role(),
                row.content(),
                tree != null ? tree.components() : null,
                row.createdAt(),
                row.reasoningSummary(),
                row.traceId(),
                null,
                deserializeReactSteps(row.reactStepsJson()),
                effectiveCompletionMode,
                resolveMessageResumedFromTraceId(row, turn, currentAttemptMessage),
                effectiveTurnStatus,
                resolveMessageErrorMessage(turn, currentAttemptMessage)
        );
    }

    @Nullable
    private ChatTurnStatus resolveMessageTurnStatus(@Nullable ChatTurnRecord turn,
                                                    @Nullable com.lifepilot.agent.model.CompletionMode rowCompletionMode,
                                                    boolean currentAttemptMessage) {
        if (currentAttemptMessage && turn != null) {
            return turn.status();
        }
        return resolveHistoricalTurnStatus(rowCompletionMode);
    }

    @Nullable
    private String resolveMessageErrorMessage(@Nullable ChatTurnRecord turn, boolean currentAttemptMessage) {
        return currentAttemptMessage && turn != null ? turn.lastErrorMessage() : null;
    }

    @Nullable
    private CompletionMode resolveMessageCompletionMode(@Nullable ChatTurnRecord turn,
                                                        @Nullable CompletionMode rowCompletionMode,
                                                        boolean currentAttemptMessage) {
        if (!currentAttemptMessage) {
            return rowCompletionMode;
        }
        CompletionMode turnCompletionMode = parseCompletionMode(turn != null ? turn.completionMode() : null);
        return turnCompletionMode != null ? turnCompletionMode : rowCompletionMode;
    }

    @Nullable
    private String resolveMessageResumedFromTraceId(SessionTranscriptRepository.TranscriptMessageViewRow row,
                                                    @Nullable ChatTurnRecord turn,
                                                    boolean currentAttemptMessage) {
        if (!currentAttemptMessage) {
            return row.resumedFromTraceId();
        }
        return turn != null && turn.resumedFromTraceId() != null
                ? turn.resumedFromTraceId()
                : row.resumedFromTraceId();
    }

    private boolean isCurrentAttemptMessage(SessionTranscriptRepository.TranscriptMessageViewRow row,
                                            @Nullable ChatTurnRecord turn) {
        if (turn == null) {
            return false;
        }
        if (!"assistant".equalsIgnoreCase(row.role())) {
            return true;
        }
        if (turn.assistantEntryId() != null && !turn.assistantEntryId().isBlank()) {
            return Objects.equals(turn.assistantEntryId(), row.entryId());
        }
        return turn.latestTraceId() != null
                && !turn.latestTraceId().isBlank()
                && row.traceId() != null
                && !row.traceId().isBlank()
                && Objects.equals(turn.latestTraceId(), row.traceId());
    }

    @Nullable
    private ChatTurnStatus resolveHistoricalTurnStatus(@Nullable CompletionMode rowCompletionMode) {
        if (rowCompletionMode == CompletionMode.SUSPENDED) {
            return ChatTurnStatus.SUSPENDED;
        }
        if (rowCompletionMode == CompletionMode.DEGRADED) {
            return ChatTurnStatus.DEGRADED;
        }
        return null;
    }

    private List<MessageInfo> withAttachments(List<MessageInfo> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        var entryIds = messages.stream().map(MessageInfo::id).toList();
        var attachmentMap = attachmentRepository.findByEntryIds(entryIds);
        if (attachmentMap.isEmpty()) {
            return messages;
        }
        return messages.stream().map(msg -> {
            var records = attachmentMap.get(msg.id());
            if (records == null || records.isEmpty()) {
                return msg;
            }
            var attachments = records.stream()
                    .map(r -> new AttachmentInfo(r.id(), r.fileName(), r.fileSize(), r.mimeType(), r.url()))
                    .toList();
            return new MessageInfo(
                    msg.id(),
                    msg.turnId(),
                    msg.role(),
                    msg.content(),
                    msg.a2uiComponents(),
                    msg.timestamp(),
                    msg.reasoningSummary(),
                    msg.traceId(),
                    attachments,
                    msg.reactSteps(),
                    msg.completionMode(),
                    msg.resumedFromTraceId(),
                    msg.turnStatus(),
                    msg.errorMessage()
            );
        }).toList();
    }

    private SessionInfo forkFromTranscript(ChatSession originalSession,
                                           List<SessionTranscriptRepository.TranscriptMessageViewRow> transcriptMessages,
                                           String fromEntryId,
                                           String newTitle) {
        int messageIndex = -1;
        for (int i = 0; i < transcriptMessages.size(); i++) {
            if (transcriptMessages.get(i).entryId().equals(fromEntryId)) {
                messageIndex = i;
                break;
            }
        }
        if (messageIndex == -1) {
            throw new IllegalArgumentException("\u5206\u53c9\u8d77\u70b9\u6761\u76ee\u4e0d\u5b58\u5728: entryId=" + fromEntryId);
        }

        List<SessionTranscriptRepository.TranscriptMessageViewRow> messagesToCopy =
                transcriptMessages.subList(0, messageIndex + 1);
        if (messagesToCopy.isEmpty()) {
            throw new IllegalArgumentException("\u6ca1\u6709\u53ef\u5206\u53c9\u7684\u6d88\u606f");
        }

        ChatSession newSession = createForkSession(originalSession, newTitle);
        Map<String, String> copiedEntryIds = new LinkedHashMap<>();
        for (SessionTranscriptRepository.TranscriptMessageViewRow originalMsg : messagesToCopy) {
            SessionTranscriptRepository.SessionTranscriptEntryRow sourceRow = transcriptRepository.findById(originalMsg.entryId())
                    .orElseThrow(() -> new IllegalStateException("transcript \u6761\u76ee\u4e0d\u5b58\u5728: id=" + originalMsg.entryId()));
            String copiedEntryId = transcriptRepository.copyEntry(newSession.id(), sourceRow);
            copiedEntryIds.put(originalMsg.entryId(), copiedEntryId);
            sessionRepository.appendMessageMeta(newSession.id(), sourceRow.createdAt(), preview(originalMsg.content()));
        }
        attachmentRepository.copyForFork(copiedEntryIds, newSession.id());
        copyKnowledgeBaseBindings(originalSession.id(), newSession.id());
        copyDatastoreBindings(originalSession.id(), newSession.id());

        log.info("\u5206\u53c9\u4f1a\u8bdd(transcript): originalSessionId={}, newSessionId={}, messageCount={}",
                originalSession.id(), newSession.id(), messagesToCopy.size());
        return toSessionInfo(sessionRepository.findById(newSession.id()).orElse(newSession));
    }

    private ChatSession createForkSession(ChatSession originalSession, String newTitle) {
        String title = newTitle != null && !newTitle.isBlank()
                ? newTitle
                : originalSession.title() + " (fork)";
        // 分叉会话必须继承源会话的 projectId，避免归属项目的对话被 fork 到主账户
        ChatSession newSession = ChatSession.create(title, originalSession.projectId());
        sessionRepository.save(newSession);
        return newSession;
    }

    private void copyKnowledgeBaseBindings(String sourceSessionId, String targetSessionId) {
        List<String> knowledgeBaseIds = sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(sourceSessionId);
        for (String knowledgeBaseId : knowledgeBaseIds) {
            sessionKnowledgeBaseRepository.addAssociation(targetSessionId, knowledgeBaseId);
        }
    }

    private void copyDatastoreBindings(String sourceSessionId, String targetSessionId) {
        List<String> datastoreIds = sessionDatastoreRepository.findDatastoreIdsBySessionId(sourceSessionId);
        for (String datastoreId : datastoreIds) {
            sessionDatastoreRepository.addAssociation(targetSessionId, datastoreId);
        }
    }

    @Nullable
    private CompletionMode parseCompletionMode(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return CompletionMode.valueOf(rawValue.strip());
        } catch (IllegalArgumentException e) {
            log.warn("completion_mode \u53cd\u5e8f\u5217\u5316\u5931\u8d25: value={}", rawValue);
            return null;
        }
    }

    @Nullable
    private List<Map<String, Object>> deserializeReactSteps(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );
        } catch (Exception e) {
            log.warn("react_steps_json \u53cd\u5e8f\u5217\u5316\u5931\u8d25: error={}", e.getMessage());
            return null;
        }
    }

    private ChatSession requireWebSession(String id) {
        if (!isWebSessionId(id)) {
            throw new IllegalArgumentException("\u4ec5\u652f\u6301\u8bbf\u95ee Web \u4f1a\u8bdd: id=" + id);
        }
        return requireSession(id);
    }

    private boolean isWebSessionId(String id) {
        return id != null && !id.isBlank() && !id.contains(":");
    }

    private ChatSession requireSession(String id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("\u4f1a\u8bdd\u4e0d\u5b58\u5728: id=" + id));
    }

    private String preview(String content) {
        if (content == null) {
            return null;
        }
        String text = content.strip();
        if (text.length() <= 100) {
            return text;
        }
        return text.substring(0, 100) + "...";
    }

    private SessionInfo toSessionInfo(ChatSession session) {
        return new SessionInfo(
                session.id(),
                session.title(),
                session.createdAt(),
                session.updatedAt(),
                session.isPinned(),
                session.archived(),
                session.summary(),
                session.lastMessageAt(),
                session.projectId()
        );
    }

    private SessionCompactionStatusInfo buildCompactionStatus(String sessionId, Map<String, Object> sessionConfig) {
        if (agentConfig == null || compactionBoundaryResolver == null || sessionStoreRepository == null) {
            return new SessionCompactionStatusInfo(
                    false,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    false,
                    false,
                    0,
                    null
            );
        }
        AgentConfigProperties.ContextConfig.CompactionConfig compactionConfig = agentConfig.getContext().getCompaction();
        boolean enabled = compactionConfig == null || compactionConfig.isEnabled();
        int triggerThresholdPercent = resolveTriggerThresholdPercent(compactionConfig);
        int effectiveContextWindow = resolveEffectiveContextWindow(SessionConfigKeys.resolvePreferredProviderId(sessionConfig));
        int triggerThresholdTokens = Math.max(1, effectiveContextWindow * triggerThresholdPercent / 100);
        int minTurnCount = resolveMinTurnCount(compactionConfig);
        int keepRecentTurns = resolveKeepRecentTurns(compactionConfig);

        List<SessionTranscriptRepository.SessionTranscriptEntryRow> allRows = transcriptRepository.findBySessionId(sessionId);
        TranscriptCompactionBoundaryResolver.CompactionBoundary boundary =
                compactionBoundaryResolver.resolveLatest(allRows).orElse(null);
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeRows =
                compactionBoundaryResolver.filterRowsForActiveContext(allRows, boundary);
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> activeVisibleRows = activeRows.stream()
                .filter(SessionTranscriptRepository.SessionTranscriptEntryRow::visibleToModel)
                .filter(row -> !TranscriptEntryType.COMPACTION_SUMMARY.value().equals(row.entryType()))
                .toList();

        int activeTranscriptTokens = activeVisibleRows.stream()
                .mapToInt(row -> Math.max(0, row.tokenEstimate()))
                .sum();
        int activeTurnCount = countCompleteTurns(activeVisibleRows);
        boolean thresholdReached = activeTranscriptTokens >= triggerThresholdTokens;
        boolean minTurnsReached = activeTurnCount >= minTurnCount;
        boolean readyToCompact = enabled
                && thresholdReached
                && minTurnsReached
                && activeTurnCount > keepRecentTurns;
        int remainingTokens = Math.max(0, triggerThresholdTokens - activeTranscriptTokens);
        int compactionCount = sessionStoreRepository.findBySessionId(sessionId)
                .map(SessionStoreRepository.SessionStoreRow::compactionCount)
                .orElse(0);

        return new SessionCompactionStatusInfo(
                enabled,
                activeTranscriptTokens,
                triggerThresholdTokens,
                triggerThresholdPercent,
                remainingTokens,
                activeTurnCount,
                minTurnCount,
                keepRecentTurns,
                thresholdReached,
                minTurnsReached,
                readyToCompact,
                compactionCount,
                boundary != null ? boundary.createdAt() : null
        );
    }

    private int resolveEffectiveContextWindow(@Nullable String preferredProviderId) {
        int configuredWindow = Math.max(1024, agentConfig.getContext().getMaxContextTokens());
        if (generationRouter == null) {
            return configuredWindow;
        }
        try {
            int providerWindow = generationRouter.resolveMaxContextWindow(
                    agentConfig.getLoop().getLlmScene(),
                    preferredProviderId,
                    null
            );
            if (providerWindow > 0) {
                return Math.min(configuredWindow, providerWindow);
            }
        } catch (Exception e) {
            log.debug("读取会话 Provider 上下文窗口失败，回退默认配置: sessionPreferredProvider={}, error={}",
                    preferredProviderId, e.getMessage());
        }
        return configuredWindow;
    }

    private int countCompleteTurns(List<SessionTranscriptRepository.SessionTranscriptEntryRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        List<List<SessionTranscriptRepository.SessionTranscriptEntryRow>> turns = new ArrayList<>();
        List<SessionTranscriptRepository.SessionTranscriptEntryRow> current = new ArrayList<>();
        boolean hasAssistantReply = false;

        for (SessionTranscriptRepository.SessionTranscriptEntryRow row : rows) {
            TranscriptEntryType entryType = TranscriptEntryType.fromValue(row.entryType());
            if (entryType == TranscriptEntryType.USER_MESSAGE) {
                if (!current.isEmpty() && hasAssistantReply) {
                    turns.add(List.copyOf(current));
                }
                current = new ArrayList<>();
                current.add(row);
                hasAssistantReply = false;
                continue;
            }
            if (current.isEmpty()) {
                continue;
            }
            current.add(row);
            if (entryType == TranscriptEntryType.ASSISTANT_MESSAGE) {
                hasAssistantReply = true;
            }
        }
        if (!current.isEmpty() && hasAssistantReply) {
            turns.add(List.copyOf(current));
        }
        return turns.size();
    }

    private int resolveTriggerThresholdPercent(@Nullable AgentConfigProperties.ContextConfig.CompactionConfig compactionConfig) {
        int configured = compactionConfig != null ? compactionConfig.getTriggerThresholdPercent() : 0;
        return configured > 0 ? configured : 75;
    }

    private int resolveMinTurnCount(@Nullable AgentConfigProperties.ContextConfig.CompactionConfig compactionConfig) {
        int configured = compactionConfig != null ? compactionConfig.getMinTurnCount() : 0;
        return configured > 0 ? configured : 6;
    }

    private int resolveKeepRecentTurns(@Nullable AgentConfigProperties.ContextConfig.CompactionConfig compactionConfig) {
        int configured = compactionConfig != null ? compactionConfig.getKeepRecentTurns() : 0;
        return configured > 0 ? configured : 2;
    }
}
