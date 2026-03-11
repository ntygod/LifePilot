package com.lifepilot.interaction.web.service;

import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.interaction.web.repository.ChatMessageRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * JDBC 实现的对话历史存储（chat_sessions + chat_messages）。
 *
 * <p>与 L1-L4 记忆系统物理解耦：不写入 conversations/messages（memory tables）。</p>
 *
 * @author zsg
 * @since 2026-03-03
 */
@Service
public class JdbcConversationHistoryStore implements ConversationHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcConversationHistoryStore.class);

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    public JdbcConversationHistoryStore(ChatSessionRepository sessionRepository,
                                       ChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 同步写入用户消息，返回后端生成的 messageId。
     *
     * <p>调用方需确保会话已预创建。</p>
     *
     * @since 2026-03-06
     */
    @Override
    @Transactional
    public String appendUserMessage(String sessionId,
                                    String userMessage,
                                    @Nullable String traceId) {
        Instant ts = Instant.now();
        String messageId = messageRepository.insert(sessionId, "user", userMessage, null, traceId, ts, null);
        sessionRepository.appendMessageMeta(sessionId, ts, truncatePreview(userMessage));
        log.debug("用户消息已同步写入: sessionId={}, messageId={}", sessionId, messageId);
        return messageId;
    }

    /**
     * 同步写入助手消息，返回后端生成的 messageId。
     *
     * <p>调用方需确保会话已预创建。</p>
     *
     * @since 2026-03-06
     */
    @Override
    @Transactional
    public String appendAssistantMessage(String sessionId,
                                         String assistantMessage,
                                         @Nullable String reasoningSummary,
                                         @Nullable String traceId,
                                         @Nullable String a2uiComponentsJson) {
        Instant ts = Instant.now();
        String messageId = messageRepository.insert(sessionId, "assistant", assistantMessage,
                reasoningSummary, traceId, ts, a2uiComponentsJson);
        sessionRepository.appendMessageMeta(sessionId, ts, truncatePreview(assistantMessage));
        log.debug("助手消息已同步写入: sessionId={}, messageId={}", sessionId, messageId);
        return messageId;
    }

    /**
     * 追加一轮对话（向后兼容 CLI 等非 Web 渠道）。
     *
     * <p>调用方需确保会话已预创建（Web 渠道在打开新对话时创建，CLI 渠道由适配器负责）。</p>
     */
    @Override
    @Transactional
    public void appendTurn(String sessionId,
                           @Nullable String userMessage,
                           @Nullable String assistantMessage,
                           @Nullable String reasoningSummary,
                           @Nullable String traceId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

        if (userMessage != null && !userMessage.isBlank()) {
            appendUserMessage(sessionId, userMessage, traceId);
        }
        if (assistantMessage != null && !assistantMessage.isBlank()) {
            appendAssistantMessage(sessionId, assistantMessage, reasoningSummary, traceId, null);
        }

        log.debug("对话历史已追加: sessionId={}, hasUser={}, hasAssistant={}",
                sessionId,
                userMessage != null && !userMessage.isBlank(),
                assistantMessage != null && !assistantMessage.isBlank());
    }

    @Override
    @Transactional
    public void appendSystemMessage(String sessionId,
                                    @Nullable String systemMessage,
                                    @Nullable String traceId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (systemMessage == null || systemMessage.isBlank()) {
            return;
        }

        Instant ts = Instant.now();
        messageRepository.insert(sessionId, "system", systemMessage, null, traceId, ts, null);
        sessionRepository.appendMessageMeta(sessionId, ts, truncatePreview(systemMessage));

        log.debug("系统消息已追加: sessionId={}, traceIdPresent={}",
                sessionId, traceId != null && !traceId.isBlank());
    }

    private String truncatePreview(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        if (t.length() <= 100) {
            return t;
        }
        return t.substring(0, 100) + "...";
    }
}
