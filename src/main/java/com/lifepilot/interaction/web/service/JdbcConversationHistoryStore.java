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

        // 确保 chat_sessions 存在（避免非 Web 渠道或异常流程导致外键失败）
        sessionRepository.ensureExists(sessionId);

        Instant base = Instant.now();

        // 1) user 消息
        if (userMessage != null && !userMessage.isBlank()) {
            messageRepository.insert(sessionId, "user", userMessage, null, traceId, base);
            sessionRepository.appendMessageMeta(sessionId, base, truncatePreview(userMessage));
        }

        // 2) assistant 消息（优先用 assistant 做 sidebar 预览）
        if (assistantMessage != null && !assistantMessage.isBlank()) {
            Instant ts = base.plusMillis(1);
            messageRepository.insert(sessionId, "assistant", assistantMessage, reasoningSummary, traceId, ts);
            sessionRepository.appendMessageMeta(sessionId, ts, truncatePreview(assistantMessage));
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

        // 确保 chat_sessions 存在（避免非 Web 渠道或异常流程导致外键失败）
        sessionRepository.ensureExists(sessionId);

        Instant ts = Instant.now();
        messageRepository.insert(sessionId, "system", systemMessage, null, traceId, ts);
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

