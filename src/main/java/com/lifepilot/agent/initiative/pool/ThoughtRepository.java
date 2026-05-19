package com.lifepilot.agent.initiative.pool;

import com.lifepilot.agent.initiative.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 想法持久化仓库 — 对接 initiative_thoughts 表。
 *
 * @author zsg
 * @since 2026-06-01
 */
public class ThoughtRepository {

    private static final Logger log = LoggerFactory.getLogger(ThoughtRepository.class);
    private static final TypeReference<List<Evidence>> EVIDENCE_LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ThoughtRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(Thought thought) {
        String evidenceJson;
        try {
            evidenceJson = objectMapper.writeValueAsString(thought.evidence());
        } catch (Exception e) {
            evidenceJson = "[]";
        }

        jdbcTemplate.update("""
            INSERT INTO initiative_thoughts (id, intent_key, kind, summary, evidence_json,
                confidence, maturity, state, action_pattern, conversation_id,
                created_at, mature_at, expressed_at, resolved_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                maturity = excluded.maturity,
                state = excluded.state,
                conversation_id = excluded.conversation_id,
                expressed_at = excluded.expressed_at,
                resolved_at = excluded.resolved_at
            """,
                thought.id(), thought.intentKey(), thought.kind().name(),
                thought.summary(), evidenceJson,
                thought.confidence(), thought.maturity(), thought.state().name(),
                null, thought.conversationId(),
                thought.createdAt().toString(),
                thought.matureAt() != null ? thought.matureAt().toString() : null,
                null, null
        );
    }

    public Optional<Thought> findById(String id) {
        var results = jdbcTemplate.query(
                "SELECT * FROM initiative_thoughts WHERE id = ?",
                (rs, rowNum) -> mapRow(rs), id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<Thought> findByState(ThoughtState state) {
        return jdbcTemplate.query(
                "SELECT * FROM initiative_thoughts WHERE state = ? ORDER BY maturity DESC",
                (rs, rowNum) -> mapRow(rs), state.name());
    }

    public List<Thought> findActiveByIntentKey(String intentKey) {
        return jdbcTemplate.query(
                "SELECT * FROM initiative_thoughts WHERE intent_key = ? AND state IN ('BREWING', 'READY')",
                (rs, rowNum) -> mapRow(rs), intentKey);
    }

    public void updateState(String id, ThoughtState newState) {
        String resolvedAt = newState.isTerminal() ? Instant.now().toString() : null;
        String expressedAt = newState == ThoughtState.EXPRESSED ? Instant.now().toString() : null;
        jdbcTemplate.update(
                "UPDATE initiative_thoughts SET state = ?, expressed_at = COALESCE(expressed_at, ?), resolved_at = COALESCE(resolved_at, ?) WHERE id = ?",
                newState.name(), expressedAt, resolvedAt, id);
    }

    public void updateMaturity(String id, float maturity, ThoughtState state) {
        jdbcTemplate.update(
                "UPDATE initiative_thoughts SET maturity = ?, state = ? WHERE id = ?",
                maturity, state.name(), id);
    }

    public int deleteTerminalOlderThan(Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM initiative_thoughts WHERE state IN ('DISMISSED', 'ABSORBED') AND resolved_at < ?",
                cutoff.toString());
    }

    private Thought mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        List<Evidence> evidence;
        try {
            evidence = objectMapper.readValue(rs.getString("evidence_json"), EVIDENCE_LIST_TYPE);
        } catch (Exception e) {
            evidence = List.of();
        }
        String matureAtStr = rs.getString("mature_at");
        return new Thought(
                rs.getString("id"),
                rs.getString("intent_key"),
                ThoughtKind.valueOf(rs.getString("kind")),
                rs.getString("summary"),
                evidence,
                rs.getFloat("confidence"),
                rs.getFloat("maturity"),
                Instant.parse(rs.getString("created_at")),
                matureAtStr != null ? Instant.parse(matureAtStr) : null,
                ThoughtState.valueOf(rs.getString("state")),
                rs.getString("conversation_id")
        );
    }
}
