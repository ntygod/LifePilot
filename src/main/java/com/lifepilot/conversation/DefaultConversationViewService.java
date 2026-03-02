package com.lifepilot.conversation;

import com.lifepilot.agent.session.ConversationTurn;
import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.agent.session.SessionSnapshot;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 默认的会话视图服务实现。
 *
 * <p>
 * 读路径聚合自：
 * <ul>
 *     <li>L0：{@link SessionManager} / {@link SessionSnapshot}（最近 N 轮对话快照）</li>
 *     <li>L2：{@link EpisodicMemory}（按 sessionId 归档的情景记忆对话记录）</li>
 * </ul>
 * </p>
 *
 * <p>
 * 该实现仅用于只读视图聚合，不承担任何写入职责，
 * 避免与会话层和记忆层的具体持久化策略产生耦合。
 * </p>
 */
public class DefaultConversationViewService implements ConversationViewService {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationViewService.class);

    private final SessionManager sessionManager;
    private final EpisodicMemory episodicMemory;

    public DefaultConversationViewService(SessionManager sessionManager,
                                          EpisodicMemory episodicMemory) {
        this.sessionManager = sessionManager;
        this.episodicMemory = episodicMemory;
    }

    @Override
    public Optional<ConversationSessionView> getSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return sessionManager.findSession(sessionId)
                .map(this::mapToSessionView);
    }

    @Override
    public List<ConversationTurnView> getRecentTurns(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            Optional<SessionSnapshot> snapshotOpt = sessionManager.findSession(sessionId);
            if (snapshotOpt.isEmpty()) {
                return List.of();
            }
            SessionSnapshot snapshot = snapshotOpt.get();
            List<ConversationTurn> turns = snapshot.recentTurns();
            if (turns.isEmpty()) {
                return List.of();
            }

            List<ConversationTurnView> views = new ArrayList<>();
            // recentTurns 已按时间顺序存储，这里从尾部开始倒序计数，再恢复为正序
            int remaining = limit;
            for (int i = turns.size() - 1; i >= 0 && remaining > 0; i--) {
                ConversationTurn turn = turns.get(i);
                Instant timestamp = turn.timestamp();
                String reasoningSummary = turn.reasoningSummary();

                String userMessage = turn.userMessage();
                if (userMessage != null && !userMessage.isBlank() && remaining > 0) {
                    views.add(new ConversationTurnView(
                            snapshot.sessionId(),
                            "USER",
                            userMessage,
                            timestamp,
                            null
                    ));
                    remaining--;
                }

                String agentResponse = turn.agentResponse();
                if (agentResponse != null && !agentResponse.isBlank() && remaining > 0) {
                    views.add(new ConversationTurnView(
                            snapshot.sessionId(),
                            "ASSISTANT",
                            agentResponse,
                            timestamp,
                            reasoningSummary
                    ));
                    remaining--;
                }
            }

            // 目前 views 中按时间倒序（最近在前），统一改为时间正序返回
            views.sort(Comparator.comparing(ConversationTurnView::createdAt));
            return List.copyOf(views);
        } catch (Exception e) {
            log.warn("获取最近对话视图失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ConversationTurnView> getFullTimeline(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            // 优先使用 L2 中按 sessionId 归档的消息记录
            List<MessageRecord> messages = episodicMemory.getMessagesBySessionId(sessionId);
            if (!messages.isEmpty()) {
                List<ConversationTurnView> views = messages.stream()
                        .map(msg -> new ConversationTurnView(
                                sessionId,
                                msg.role(),
                                msg.effectiveContent(),
                                msg.createdAt(),
                                null
                        ))
                        .sorted(Comparator.comparing(ConversationTurnView::createdAt))
                        .toList();
                return List.copyOf(views);
            }

            // 若尚未归档到 L2，则退化为 recentTurns 视图
            return getRecentTurns(sessionId, Integer.MAX_VALUE);
        } catch (Exception e) {
            log.warn("获取完整对话时间线视图失败: sessionId={}, error={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private ConversationSessionView mapToSessionView(SessionSnapshot snapshot) {
        return new ConversationSessionView(
                snapshot.sessionId(),
                snapshot.channelId(),
                snapshot.lastActiveAt(),
                snapshot.totalTurns(),
                snapshot.totalTokensUsed()
        );
    }
}

