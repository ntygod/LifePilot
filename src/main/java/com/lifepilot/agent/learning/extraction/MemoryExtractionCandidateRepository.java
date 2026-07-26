package com.lifepilot.agent.learning.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 记忆提取候选仓库。
 *
 * <p>RealtimeExtractor 将 AUDN 决策按质量门控结果落到候选表：通过者进入
 * VALIDATED 并继续 governed upsert/archive，拒绝者进入 REJECTED 并保留原因。
 * 这样可以审计 LLM 输出、重放失败候选，也能区分“模型提到”与“最终写入主库”。</p>
 *
 * @author zsg
 * @since 2026-05-05
 */
public class MemoryExtractionCandidateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MemoryExtractionCandidateRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空");
    }

    /**
     * 记录已通过质量门控的候选。
     *
     * @return candidate id
     */
    public String recordValidated(String sessionId,
                                  MemoryWriteContext writeContext,
                                  AudnDecision decision) {
        return recordCandidate(sessionId, writeContext, decision, "VALIDATED", "VALIDATED", null,
                validatedQualityOf(decision));
    }

    /**
     * 记录被质量门控拒绝的候选。
     *
     * @return candidate id
     */
    public String recordRejected(String sessionId,
                                 MemoryWriteContext writeContext,
                                 AudnDecision decision,
                                 String rejectionReason) {
        requireText(rejectionReason, "候选拒绝原因不能为空");
        return recordCandidate(sessionId, writeContext, decision, "REJECTED", "REJECTED", rejectionReason,
                Quality.rejected());
    }

    private String recordCandidate(String sessionId,
                                   MemoryWriteContext writeContext,
                                   AudnDecision decision,
                                   String candidateStatus,
                                   String validationStatus,
                                   String rejectionReason,
                                   Quality quality) {
        requireText(sessionId, "候选记录 sessionId 不能为空");
        Objects.requireNonNull(writeContext, "候选记录写入上下文不能为空");
        Objects.requireNonNull(decision, "AUDN 决策不能为空");
        requireText(candidateStatus, "候选状态不能为空");
        requireText(validationStatus, "候选校验状态不能为空");
        Objects.requireNonNull(quality, "候选质量字段不能为空");
        String operation = Objects.requireNonNull(decision.operation(), "AUDN 操作不能为空").name();
        String entityName = requireText(decision.entityName(), "AUDN 实体名称不能为空");
        String entityType = Objects.requireNonNull(decision.entityType(), "AUDN 实体类型不能为空").name();

        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String decisionJson = serializeDecision(decision);
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO memory_extraction_candidates(
                        id, session_id, turn_id, source_entry_id, target_space_id,
                        operation, entity_name, entity_type, decision_json,
                        candidate_status, validation_status, rejection_reason,
                        evidence_kind, trust_level, trust_score, evidence_excerpt,
                        created_at, updated_at
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    id,
                    sessionId,
                    writeContext.sourceTurnId(),
                    writeContext.sourceEntryId(),
                    writeContext.spaceId(),
                    operation,
                    entityName,
                    entityType,
                    decisionJson,
                    candidateStatus,
                    validationStatus,
                    rejectionReason,
                    quality.evidenceKind().name(),
                    quality.trustLevel().name(),
                    quality.trustScore(),
                    decision.evidenceExcerpt(),
                    now,
                    now);
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("记忆提取候选写入失败: sessionId=%s, entity=%s"
                    .formatted(sessionId, decision.entityName()), e);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private Quality validatedQualityOf(AudnDecision decision) {
        MemoryEvidenceKind evidenceKind = MemoryQualityPolicy.evidenceKindFromDecision(decision);
        if (decision.extractionConfidence() == null) {
            throw new IllegalArgumentException("已验证候选缺少 extractionConfidence");
        }
        float extractionConfidence = decision.extractionConfidence();
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, extractionConfidence);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
        return new Quality(evidenceKind, trustLevel, trustScore);
    }

    private record Quality(
            MemoryEvidenceKind evidenceKind,
            MemoryTrustLevel trustLevel,
            float trustScore
    ) {
        private Quality {
            Objects.requireNonNull(evidenceKind, "候选证据类型不能为空");
            Objects.requireNonNull(trustLevel, "候选可信等级不能为空");
            if (!(trustScore >= 0.0f && trustScore <= 1.0f)) {
                throw new IllegalArgumentException("候选可信分必须在 [0,1] 范围内: " + trustScore);
            }
        }

        static Quality rejected() {
            return new Quality(MemoryEvidenceKind.UNKNOWN, MemoryTrustLevel.UNVERIFIED, 0.0f);
        }
    }

    public void markApplied(String candidateId,
                            String persistedEntityId,
                            String baseEntityId) {
        requireText(candidateId, "候选记录 id 不能为空");
        int updated = jdbcTemplate.update(
                """
                UPDATE memory_extraction_candidates
                SET candidate_status = 'APPLIED',
                    persisted_entity_id = ?,
                    base_entity_id = ?,
                    error_message = NULL,
                    updated_at = ?
                WHERE id = ?
                """,
                persistedEntityId,
                baseEntityId,
                Instant.now().toString(),
                candidateId);
        requireUpdatedCandidate(updated, candidateId, "APPLIED");
    }

    public void markFailed(String candidateId, String errorMessage) {
        requireText(candidateId, "候选记录 id 不能为空");
        int updated = jdbcTemplate.update(
                """
                UPDATE memory_extraction_candidates
                SET candidate_status = 'FAILED',
                    error_message = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                errorMessage,
                Instant.now().toString(),
                candidateId);
        requireUpdatedCandidate(updated, candidateId, "FAILED");
    }

    public void markTurnRunning(String sessionId, String turnId) {
        markTurnStatus(sessionId, turnId, "RUNNING", null, false);
    }

    public void markTurnCompleted(String sessionId, String turnId) {
        markTurnStatus(sessionId, turnId, "COMPLETED", null, true);
    }

    public void markTurnFailed(String sessionId, String turnId, String errorMessage) {
        markTurnStatus(sessionId, turnId, "FAILED", errorMessage, true);
    }

    private void markTurnStatus(String sessionId,
                                String turnId,
                                String status,
                                @Nullable String reason,
                                boolean terminal) {
        requireText(sessionId, "轮次记忆抽取 sessionId 不能为空");
        requireText(turnId, "轮次记忆抽取 turnId 不能为空");
        requireText(status, "轮次记忆抽取状态不能为空");
        String now = Instant.now().toString();
        jdbcTemplate.update(
                """
                INSERT INTO memory_extraction_turn_status(
                    turn_id, session_id, status, reason, started_at, completed_at, updated_at
                ) VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(turn_id) DO UPDATE SET
                    session_id = excluded.session_id,
                    status = excluded.status,
                    reason = excluded.reason,
                    completed_at = excluded.completed_at,
                    updated_at = excluded.updated_at
                """,
                turnId,
                sessionId,
                status,
                cleanReason(reason),
                now,
                terminal ? now : null,
                now);
    }

    private static @Nullable String cleanReason(@Nullable String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String clean = reason.strip();
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    public Optional<TurnExtractionStatus> findTurnExtractionStatus(String turnId) {
        requireText(turnId, "轮次记忆抽取 turnId 不能为空");
        var results = jdbcTemplate.query(
                """
                SELECT turn_id, session_id, status, reason, started_at, completed_at, updated_at
                FROM memory_extraction_turn_status
                WHERE turn_id = ?
                LIMIT 1
                """,
                this::mapTurnExtractionStatus,
                turnId);
        return results.stream().findFirst();
    }

    /**
     * 删除指定会话下的记忆提取辅助记录。
     *
     * <p>候选和轮次状态用于会话回放、解释与审计；会话被清空或删除后，
     * 这些记录不应继续保留已失去来源的对话片段。已落库的记忆实体不在这里删除。</p>
     *
     * @param sessionId 会话 ID
     * @return 删除行数
     */
    public int deleteBySessionId(String sessionId) {
        requireText(sessionId, "记忆提取清理 sessionId 不能为空");
        int statusRows = jdbcTemplate.update(
                "DELETE FROM memory_extraction_turn_status WHERE session_id = ?",
                sessionId);
        int candidateRows = jdbcTemplate.update(
                "DELETE FROM memory_extraction_candidates WHERE session_id = ?",
                sessionId);
        return statusRows + candidateRows;
    }

    /**
     * 查询某轮对话已经真正落库的记忆变更。
     *
     * <p>返回 ADD / UPDATE / DELETE 且有 {@code persisted_entity_id} 的 APPLIED 候选；
     * 记住和忘记都需要在主对话里可见，rejected / failed / noop 留给审计视图。</p>
     */
    public List<AppliedMemoryChange> findAppliedMemoryChangesByTurnId(String turnId, int limit) {
        return findAppliedMemoryChangesByTurnId(turnId, limit, null, true);
    }

    /**
     * 查询某轮对话在指定写入空间内真正落库的记忆变更。
     *
     * <p>{@code targetSpaceIds == null} 表示不限制空间；空集合配合
     * {@code includeDefaultSpace=false} 会直接返回空，避免生成无意义 SQL。</p>
     */
    public List<AppliedMemoryChange> findAppliedMemoryChangesByTurnId(
            String turnId,
            int limit,
            @Nullable List<String> targetSpaceIds,
            boolean includeDefaultSpace) {
        requireText(turnId, "候选记录 turnId 不能为空");
        int safeLimit = Math.max(1, Math.min(limit, 20));
        var args = new java.util.ArrayList<Object>();
        args.add(turnId);
        String spaceCondition = buildTargetSpaceCondition(targetSpaceIds, includeDefaultSpace, args);
        if (spaceCondition == null) {
            return List.of();
        }
        args.add(safeLimit);
        return jdbcTemplate.query(
                """
                SELECT id, target_space_id, operation, entity_name, entity_type, persisted_entity_id, decision_json,
                       evidence_kind, trust_level, trust_score, evidence_excerpt, created_at
                FROM memory_extraction_candidates
                WHERE turn_id = ?
                  AND candidate_status = 'APPLIED'
                  AND operation IN ('ADD', 'UPDATE', 'DELETE')
                  AND persisted_entity_id IS NOT NULL
                  AND persisted_entity_id <> ''
                """ + spaceCondition + """
                ORDER BY created_at DESC
                LIMIT ?
                """,
                this::mapAppliedMemoryChange,
                args.toArray());
    }

    @Nullable
    private String buildTargetSpaceCondition(
            @Nullable List<String> targetSpaceIds,
            boolean includeDefaultSpace,
            java.util.List<Object> args) {
        if (targetSpaceIds == null) {
            return "\n";
        }
        var cleanSpaceIds = targetSpaceIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (cleanSpaceIds.isEmpty() && !includeDefaultSpace) {
            return null;
        }
        var clauses = new java.util.ArrayList<String>();
        if (includeDefaultSpace) {
            clauses.add("(target_space_id IS NULL OR target_space_id = '')");
        }
        if (!cleanSpaceIds.isEmpty()) {
            clauses.add("target_space_id IN (" + "?,".repeat(cleanSpaceIds.size()).replaceAll(",$", "") + ")");
            args.addAll(cleanSpaceIds);
        }
        return "                  AND (" + String.join(" OR ", clauses) + ")\n";
    }

    private AppliedMemoryChange mapAppliedMemoryChange(ResultSet rs, int rowNum) throws SQLException {
        AudnDecision decision = readDecision(rs.getString("decision_json"), rs.getString("id"));
        return new AppliedMemoryChange(
                rs.getString("id"),
                rs.getString("target_space_id"),
                rs.getString("operation"),
                rs.getString("entity_name"),
                rs.getString("entity_type"),
                rs.getString("persisted_entity_id"),
                decision != null ? decision.description() : null,
                decision != null ? decision.importanceScore() : null,
                decision != null ? decision.temporalityRaw() : null,
                decision != null ? decision.expiresAtRaw() : null,
                rs.getString("evidence_kind"),
                rs.getString("trust_level"),
                rs.getFloat("trust_score"),
                rs.getString("evidence_excerpt"),
                Instant.parse(rs.getString("created_at")));
    }

    private TurnExtractionStatus mapTurnExtractionStatus(ResultSet rs, int rowNum) throws SQLException {
        return new TurnExtractionStatus(
                rs.getString("turn_id"),
                rs.getString("session_id"),
                rs.getString("status"),
                rs.getString("reason"),
                Instant.parse(rs.getString("started_at")),
                parseNullableInstant(rs.getString("completed_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    @Nullable
    private static Instant parseNullableInstant(@Nullable String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    @Nullable
    private AudnDecision readDecision(@Nullable String decisionJson, String candidateId) {
        if (decisionJson == null || decisionJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(decisionJson, AudnDecision.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("记忆提取候选 decision_json 解析失败: candidateId=" + candidateId, e);
        }
    }

    private static void requireUpdatedCandidate(int updated, String candidateId, String status) {
        if (updated != 1) {
            throw new IllegalStateException("记忆提取候选状态更新失败: candidateId=%s, status=%s, updated=%d"
                    .formatted(candidateId, status, updated));
        }
    }

    private String serializeDecision(AudnDecision decision) {
        try {
            return objectMapper.writeValueAsString(decision);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "记忆提取候选 decision_json 序列化失败: entity=" + decision.entityName(), e);
        }
    }

    public record AppliedMemoryChange(
            String candidateId,
            @Nullable String targetSpaceId,
            String operation,
            String entityName,
            String entityType,
            String persistedEntityId,
            @Nullable String description,
            @Nullable Float importanceScore,
            @Nullable String temporality,
            @Nullable String expiresAt,
            String evidenceKind,
            String trustLevel,
            float trustScore,
            @Nullable String evidenceExcerpt,
            Instant createdAt
    ) {}

    public record TurnExtractionStatus(
            String turnId,
            String sessionId,
            String status,
            @Nullable String reason,
            Instant startedAt,
            @Nullable Instant completedAt,
            Instant updatedAt
    ) {}

}
