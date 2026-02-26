package com.lifepilot.agent.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话管理器。
 *
 * <p>支持多轮对话的会话持久化（SQLite）和恢复。
 * 使用 JdbcTemplate 操作 agent_sessions 表，
 * JSON 序列化使用 Jackson ObjectMapper。</p>
 *
 * @author zsg
 * @since 2026-07-20
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AgentConfigProperties config;

    public SessionManager(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                          AgentConfigProperties config) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    /**
     * 查找会话。
     *
     * @param sessionId 会话 ID
     * @return 会话快照
     */
    public Optional<SessionSnapshot> findSession(String sessionId) {
        try {
            var results = jdbcTemplate.query(
                    "SELECT * FROM agent_sessions WHERE id = ? AND archived = 0",
                    (rs, rowNum) -> {
                        List<ConversationTurn> turns = deserializeTurns(
                                rs.getString("recent_turns_json"));
                        List<String> entities = deserializeStringList(
                                rs.getString("mentioned_entities_json"));
                        return new SessionSnapshot(
                                rs.getString("id"),
                                rs.getString("channel_id"),
                                turns,
                                entities,
                                Instant.parse(rs.getString("last_active_at")),
                                rs.getInt("total_turns"),
                                rs.getInt("total_tokens_used")
                        );
                    },
                    sessionId
            );
            return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
        } catch (Exception e) {
            log.warn("会话查询失败: sessionId={}, error={}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 保存会话（仅保留最近 N 轮对话）。
     *
     * <p>持久化失败时记录 WARN 日志，不抛出异常。</p>
     *
     * @param state 当前 Agent 状态
     */
    public void saveSession(AgentState state) {
        try {
            int maxTurns = config.getSession().getMaxRecentTurns();
            var existing = findSession(state.sessionId());
            String now = Instant.now().toString();

            // 构建新的对话轮次
            var newTurn = new ConversationTurn(
                    state.goal(),
                    state.finalOutput() != null ? state.finalOutput() : "",
                    List.of(),
                    Instant.now()
            );

            List<ConversationTurn> recentTurns;
            int totalTurns;
            int totalTokens;

            if (existing.isPresent()) {
                var session = existing.get();
                var turns = new java.util.ArrayList<>(session.recentTurns());
                turns.add(newTurn);
                // 截断到最近 N 轮
                recentTurns = turns.size() > maxTurns
                        ? turns.subList(turns.size() - maxTurns, turns.size())
                        : turns;
                totalTurns = session.totalTurns() + 1;
                totalTokens = session.totalTokensUsed() + state.budget().tokensUsed();
            } else {
                recentTurns = List.of(newTurn);
                totalTurns = 1;
                totalTokens = state.budget().tokensUsed();
            }

            String turnsJson = serializeTurns(recentTurns);
            String entitiesJson = serializeStringList(state.mentionedEntities());

            if (existing.isPresent()) {
                jdbcTemplate.update(
                        """
                        UPDATE agent_sessions SET recent_turns_json = ?, mentioned_entities_json = ?,
                            last_active_at = ?, total_turns = ?, total_tokens_used = ?, updated_at = ?
                        WHERE id = ?
                        """,
                        turnsJson, entitiesJson, now, totalTurns, totalTokens, now,
                        state.sessionId()
                );
            } else {
                jdbcTemplate.update(
                        """
                        INSERT INTO agent_sessions (id, channel_id, recent_turns_json,
                            mentioned_entities_json, last_active_at, total_turns,
                            total_tokens_used, archived, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                        """,
                        state.sessionId(), state.channel(), turnsJson, entitiesJson,
                        now, totalTurns, totalTokens, now, now
                );
            }

            log.debug("会话保存成功: sessionId={}, turns={}", state.sessionId(), totalTurns);
        } catch (Exception e) {
            log.warn("会话持久化失败: sessionId={}, error={}", state.sessionId(), e.getMessage());
        }
    }

    /**
     * 定时清理过期会话。
     */
    @Scheduled(fixedDelayString = "${lifepilot.agent.session.cleanup-interval-ms:300000}")
    public void cleanupExpiredSessions() {
        try {
            int timeoutMinutes = config.getSession().getTimeoutMinutes();
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
            int deleted = jdbcTemplate.update(
                    "UPDATE agent_sessions SET archived = 1, updated_at = ? WHERE last_active_at < ? AND archived = 0",
                    Instant.now().toString(), cutoff.toString()
            );
            if (deleted > 0) {
                log.info("过期会话清理完成: archived={}", deleted);
            }
        } catch (Exception e) {
            log.warn("过期会话清理失败: error={}", e.getMessage());
        }
    }

    // --- JSON 序列化/反序列化辅助方法 ---

    private String serializeTurns(List<ConversationTurn> turns) {
        try {
            return objectMapper.writeValueAsString(turns);
        } catch (JsonProcessingException e) {
            log.warn("对话轮次序列化失败: error={}", e.getMessage());
            return "[]";
        }
    }

    private List<ConversationTurn> deserializeTurns(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("对话轮次反序列化失败: error={}", e.getMessage());
            return List.of();
        }
    }

    private String serializeStringList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("字符串列表序列化失败: error={}", e.getMessage());
            return "[]";
        }
    }

    private List<String> deserializeStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("字符串列表反序列化失败: error={}", e.getMessage());
            return List.of();
        }
    }
}
