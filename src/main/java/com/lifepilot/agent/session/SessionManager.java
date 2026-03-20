package com.lifepilot.agent.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.ReactAgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 会话管理器。
 *
 * <p>负责 agent_sessions 的快照持久化与过期归档，不再承担 L1 flush 职责。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AgentConfigProperties config;

    public SessionManager(JdbcTemplate jdbcTemplate,
                          ObjectMapper objectMapper,
                          AgentConfigProperties config) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public Optional<SessionSnapshot> findSession(String sessionId) {
        try {
            var results = jdbcTemplate.query(
                    "SELECT * FROM agent_sessions WHERE id = ? AND archived = 0",
                    (rs, rowNum) -> new SessionSnapshot(
                            rs.getString("id"),
                            rs.getString("channel_id"),
                            deserializeTurns(rs.getString("recent_turns_json")),
                            deserializeStringList(rs.getString("mentioned_entities_json")),
                            Instant.parse(rs.getString("last_active_at")),
                            rs.getInt("total_turns"),
                            rs.getInt("total_tokens_used")),
                    sessionId);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
        } catch (Exception e) {
            log.warn("会话查询失败: sessionId={}, error={}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    public void saveSession(ReactAgentState state) {
        try {
            int maxTurns = config.getSession().getMaxRecentTurns();
            var existing = findSession(state.sessionId());
            String now = Instant.now().toString();

            var newTurn = new ConversationTurn(
                    state.goal(),
                    state.finalOutput() != null ? state.finalOutput() : "",
                    List.of(),
                    Instant.now(),
                    state.reasoningSummary());

            List<ConversationTurn> recentTurns;
            int totalTurns;
            int totalTokens;

            if (existing.isPresent()) {
                var session = existing.get();
                var turns = new ArrayList<>(session.recentTurns());
                turns.add(newTurn);
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
                        turnsJson, entitiesJson, now, totalTurns, totalTokens, now, state.sessionId());
            } else {
                jdbcTemplate.update(
                        """
                        INSERT INTO agent_sessions (id, channel_id, recent_turns_json,
                            mentioned_entities_json, last_active_at, total_turns,
                            total_tokens_used, archived, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET
                            recent_turns_json = excluded.recent_turns_json,
                            mentioned_entities_json = excluded.mentioned_entities_json,
                            last_active_at = excluded.last_active_at,
                            total_turns = agent_sessions.total_turns + 1,
                            total_tokens_used = agent_sessions.total_tokens_used + excluded.total_tokens_used,
                            updated_at = excluded.updated_at
                        """,
                        state.sessionId(), state.channel(), turnsJson, entitiesJson,
                        now, totalTurns, totalTokens, now, now);
            }

            log.debug("会话保存成功: sessionId={}, turns={}", state.sessionId(), totalTurns);
        } catch (Exception e) {
            log.warn("会话持久化失败: sessionId={}, error={}", state.sessionId(), e.getMessage());
        }
    }

    public void cleanupExpiredSessions() {
        try {
            int timeoutMinutes = config.getSession().getTimeoutMinutes();
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
            int archived = jdbcTemplate.update(
                    "UPDATE agent_sessions SET archived = 1, updated_at = ? WHERE last_active_at < ? AND archived = 0",
                    Instant.now().toString(), cutoff.toString());
            if (archived > 0) {
                log.info("过期会话归档完成: archived={}", archived);
            }
        } catch (Exception e) {
            log.warn("过期会话清理失败: error={}", e.getMessage());
        }
    }

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
            return objectMapper.readValue(json, new TypeReference<>() { });
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
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            log.warn("字符串列表反序列化失败: error={}", e.getMessage());
            return List.of();
        }
    }
}
