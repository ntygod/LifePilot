package com.lifepilot.memory.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.quality.MemoryEvidenceKind;
import com.lifepilot.memory.quality.MemoryQualityPolicy;
import com.lifepilot.memory.quality.MemoryTrustLevel;
import com.lifepilot.memory.scope.MemoryWriteContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
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
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 记录已通过质量门控的候选。
     *
     * @return candidate id
     */
    public String recordValidated(String sessionId,
                                  MemoryWriteContext writeContext,
                                  AudnDecision decision) {
        return recordCandidate(sessionId, writeContext, decision, "VALIDATED", "VALIDATED", null);
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
        return recordCandidate(sessionId, writeContext, decision, "REJECTED", "REJECTED", rejectionReason);
    }

    private String recordCandidate(String sessionId,
                                   MemoryWriteContext writeContext,
                                   AudnDecision decision,
                                   String candidateStatus,
                                   String validationStatus,
                                   String rejectionReason) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        Quality quality = qualityOf(decision);
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
                    decision.operation() != null ? decision.operation().name() : "UNKNOWN",
                    decision.entityName() != null ? decision.entityName() : "",
                    decision.entityType() != null ? decision.entityType().name() : "CUSTOM",
                    serializeDecision(decision),
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

    private Quality qualityOf(AudnDecision decision) {
        MemoryEvidenceKind evidenceKind = MemoryQualityPolicy.evidenceKindFromDecision(decision);
        float extractionConfidence = decision.extractionConfidence() != null
                ? decision.extractionConfidence() : 0.0f;
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, extractionConfidence);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
        return new Quality(evidenceKind, trustLevel, trustScore);
    }

    private record Quality(
            MemoryEvidenceKind evidenceKind,
            MemoryTrustLevel trustLevel,
            float trustScore
    ) {}

    public void markApplied(String candidateId,
                            String persistedEntityId,
                            String baseEntityId) {
        jdbcTemplate.update(
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
    }

    public void markFailed(String candidateId, String errorMessage) {
        jdbcTemplate.update(
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
    }

    private String serializeDecision(AudnDecision decision) {
        try {
            return objectMapper.writeValueAsString(decision);
        } catch (Exception e) {
            return "{}";
        }
    }

}
