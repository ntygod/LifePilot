package com.lifepilot.interaction.web.service;

import com.lifepilot.conversation.ConversationHistoryStore;
import com.lifepilot.interaction.web.model.ChatSession;
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
     * <p>非 Web 渠道（飞书/企微/钉钉）的 sessionId 不是 UUID，
     * 首次写入时自动创建 chat_sessions 记录，避免 FK 约束失败。</p>
     *
     * @since 2026-03-06
     */
    @Override
    @Transactional
    public String appendUserMessage(String sessionId,
                                    String userMessage,
                                    @Nullable String traceId) {
        ensureSessionExists(sessionId);
        Instant ts = Instant.now();
        String messageId = messageRepository.insert(sessionId, "user", userMessage, null, traceId, ts, null, null);
        sessionRepository.appendMessageMeta(sessionId, ts, truncatePreview(userMessage));
        log.debug("用户消息已同步写入: sessionId={}, messageId={}", sessionId, messageId);
        return messageId;
    }

    /**
     * 同步写入助手消息，返回后端生成的 messageId。
     *
     * <p>非 Web 渠道首次写入时自动创建 chat_sessions 记录。</p>
     *
     * @since 2026-03-06
     */
    @Override
    @Transactional
    public String appendAssistantMessage(String sessionId,
                                         String assistantMessage,
                                         @Nullable String reasoningSummary,
                                         @Nullable String traceId,
                                         @Nullable String a2uiComponentsJson,
                                         @Nullable String reactStepsJson) {
        ensureSessionExists(sessionId);
        Instant ts = Instant.now();
        String messageId = messageRepository.insert(sessionId, "assistant", assistantMessage,
                reasoningSummary, traceId, ts, a2uiComponentsJson, reactStepsJson);
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
            appendAssistantMessage(sessionId, assistantMessage, reasoningSummary, traceId, null, null);
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

        ensureSessionExists(sessionId);
        Instant ts = Instant.now();
        messageRepository.insert(sessionId, "system", systemMessage, null, traceId, ts, null, null);
        sessionRepository.appendMessageMeta(sessionId, ts, truncatePreview(systemMessage));

        log.debug("系统消息已追加: sessionId={}, traceIdPresent={}",
                sessionId, traceId != null && !traceId.isBlank());
    }

    /**
     * 确保 chat_sessions 记录存在，不存在则自动创建。
     *
     * <p>Web 渠道的 sessionId 是 UUID（前端预创建），非 Web 渠道的 sessionId
     * 格式为 {@code channel:chatId:userId}（如 {@code feishu:oc_xxx:ou_xxx}），
     * 首次写入消息时需要自动创建对应的 chat_sessions 记录。</p>
     *
     * @param sessionId 会话 ID
     */
    private void ensureSessionExists(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (sessionRepository.findById(sessionId).isPresent()) return;

        // 从 sessionId 格式推断渠道类型和标题
        String title = deriveSessionTitle(sessionId);
        var session = ChatSession.createWithId(sessionId, title);
        sessionRepository.save(session);
        log.info("自动创建非 Web 渠道会话: sessionId={}, title={}", sessionId, title);
    }

    /**
     * 从 sessionId 格式推断会话标题。
     *
     * <p>格式约定：{@code channel:chatId:userId}，如 {@code feishu:oc_xxx:ou_xxx}。</p>
     */
    private static String deriveSessionTitle(String sessionId) {
        if (sessionId.startsWith("feishu:")) return "飞书对话";
        if (sessionId.startsWith("wecom:")) return "企微对话";
        if (sessionId.startsWith("dingtalk:")) return "钉钉对话";
        return "外部渠道对话";
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
