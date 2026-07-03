package com.lifepilot.agent.learning.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.store.scope.MemoryWriteContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Objects;
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

}
