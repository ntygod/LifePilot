package com.lifepilot.conversation;

import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.interaction.web.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 默认会话视图服务实现。
 *
 * <p>原始对话统一从会话层读取，不再回退到旧的 L2 对话归档表。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class DefaultConversationViewService implements ConversationViewService {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationViewService.class);

    private final SessionManager sessionManager;
    private final ChatMessageRepository chatMessageRepository;

    public DefaultConversationViewService(SessionManager sessionManager,
                                          ChatMessageRepository chatMessageRepository) {
        this.sessionManager = sessionManager;
        this.chatMessageRepository = chatMessageRepository;
    }

    @Override
    public Optional<ConversationSessionView> getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return sessionManager.findSession(sessionId)
                .map(snapshot -> new ConversationSessionView(
                        snapshot.sessionId(),
                        snapshot.channelId(),
                        snapshot.lastActiveAt(),
                        snapshot.totalTurns(),
                        snapshot.totalTokensUsed()));
    }

    @Override
    public List<ConversationTurnView> getRecentTurns(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            var rows = chatMessageRepository.findRowsBySessionId(sessionId);
            if (rows.isEmpty()) {
                return List.of();
            }
            return ConversationTurnGrouper.flattenRecentCompleteTurns(rows, limit).stream()
                    .map(this::toView)
                    .toList();
        } catch (Exception e) {
            log.warn("获取最近完整对话失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ConversationTurnView> getFullTimeline(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            return chatMessageRepository.findRowsBySessionId(sessionId).stream()
                    .map(this::toView)
                    .sorted(Comparator.comparing(ConversationTurnView::createdAt))
                    .toList();
        } catch (Exception e) {
            log.warn("获取完整时间线失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private ConversationTurnView toView(ChatMessageRepository.ChatMessageRow row) {
        return new ConversationTurnView(
                row.sessionId(),
                row.role(),
                row.content(),
                row.createdAt(),
                row.reasoningSummary());
    }
}
