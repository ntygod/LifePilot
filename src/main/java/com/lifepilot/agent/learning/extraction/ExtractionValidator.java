package com.lifepilot.agent.learning.extraction;

import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.semantic.AudnOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 提取后验证器 — 对 LLM 返回的 AudnDecision 列表执行质量门控。
 *
 * <p>验证规则：
 * <ul>
 *   <li>entityName 非空且长度在 [1, maxEntityNameLength] 之间</li>
 *   <li>ADD 类型的 description 非空且长度 >= minDescriptionLength</li>
 *   <li>extractionConfidence / importanceScore 必须存在且位于 [0.0, 1.0]</li>
 *   <li>extractionConfidence >= minExtractionConfidence</li>
 *   <li>temporality 必须是 EPHEMERAL / SHORT_TERM / PERSISTENT</li>
 *   <li>单次提取数量不超过 maxEntitiesPerExtraction</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-03-12
 */
public class ExtractionValidator {

    private static final Logger log = LoggerFactory.getLogger(ExtractionValidator.class);

    private final int maxEntitiesPerExtraction;
    private final float minExtractionConfidence;
    private final int maxEntityNameLength;
    private final int minDescriptionLength;

    public ExtractionValidator(AgentLearningProperties properties) {
        var ext = properties.getExtraction();
        this.maxEntitiesPerExtraction = ext.getMaxEntitiesPerExtraction();
        this.minExtractionConfidence = ext.getMinExtractionConfidence();
        this.maxEntityNameLength = ext.getMaxEntityNameLength();
        this.minDescriptionLength = ext.getMinDescriptionLength();
    }

    /**
     * 验证并过滤 AUDN 决策列表。
     *
     * @param decisions 原始决策列表
     * @return 经过验证和过滤后的有效决策列表
     */
    public List<AudnDecision> validate(List<AudnDecision> decisions) {
        return validateWithResult(decisions).validDecisions();
    }

    /**
     * 验证并返回完整质量门控结果。
     *
     * <p>有效决策继续执行；被拒绝决策用于写入候选审计表，避免 LLM 原始输出
     * 在质量门控阶段静默丢失。</p>
     */
    public ValidationResult validateWithResult(List<AudnDecision> decisions) {
        Objects.requireNonNull(decisions, "AUDN 决策列表不能为空");
        if (decisions.isEmpty()) {
            return new ValidationResult(List.of(), List.of());
        }

        // 1. 逐条验证
        var valid = new ArrayList<AudnDecision>();
        var rejected = new ArrayList<RejectedDecision>();
        for (var d : decisions) {
            Objects.requireNonNull(d, "AUDN 决策不能为空");
            if (d.operation() == null) {
                log.debug("ExtractionValidator: 丢弃缺少 operation 的决策, entityName={}", d.entityName());
                throw new IllegalStateException("AUDN 决策 operation 不能为空: entity=" + d.entityName());
            }
            if (d.operation() == AudnOperation.NOOP) {
                continue;
            }
            requireDecisionContract(d);
            String rejectionReason = qualityRejectionReason(d);
            if (rejectionReason != null) {
                rejected.add(new RejectedDecision(d, rejectionReason));
                continue;
            }
            valid.add(d);
        }

        // 2. 数量限制：按 extractionConfidence 降序保留前 N 条
        if (valid.size() > maxEntitiesPerExtraction) {
            log.warn("AUDN 提取数量超限: original={}, limit={}",
                    valid.size(), maxEntitiesPerExtraction);
            valid.sort(Comparator.comparing(AudnDecision::extractionConfidence).reversed());
            valid.subList(maxEntitiesPerExtraction, valid.size()).stream()
                    .map(d -> new RejectedDecision(d, "MAX_ENTITIES_EXCEEDED"))
                    .forEach(rejected::add);
            valid = new ArrayList<>(valid.subList(0, maxEntitiesPerExtraction));
        }

        return new ValidationResult(List.copyOf(valid), List.copyOf(rejected));
    }

    private void requireEntityName(AudnDecision d) {
        String name = d.entityName();
        if (name == null || name.isBlank()) {
            log.debug("ExtractionValidator: 丢弃空名称决策, operation={}", d.operation());
            throw new IllegalStateException("AUDN 决策实体名称不能为空: operation=" + d.operation());
        }
        if (name.length() > maxEntityNameLength) {
            log.debug("ExtractionValidator: 丢弃超长名称决策, name={}, length={}",
                    name.substring(0, Math.min(20, name.length())) + "...", name.length());
            throw new IllegalStateException(
                    "AUDN 决策实体名称超长: name=%s, length=%d, max=%d"
                            .formatted(name, name.length(), maxEntityNameLength));
        }
    }

    private void requireDescription(AudnDecision d) {
        if (d.operation() != AudnOperation.ADD) {
            return;
        }
        String desc = d.description();
        if (desc == null || desc.isBlank() || desc.length() < minDescriptionLength) {
            log.debug("ExtractionValidator: 丢弃无描述 ADD 决策, entityName={}",
                    d.entityName());
            throw new IllegalStateException(
                    "AUDN ADD 决策描述不能为空且长度不能低于 %d: entity=%s"
                            .formatted(minDescriptionLength, d.entityName()));
        }
    }

    private boolean validateConfidence(AudnDecision d) {
        float confidence = d.extractionConfidence();
        if (confidence < minExtractionConfidence) {
            log.debug("ExtractionValidator: 丢弃低置信度决策, entityName={}, confidence={}",
                    d.entityName(), confidence);
            return false;
        }
        return true;
    }

    @Nullable
    private String qualityRejectionReason(AudnDecision d) {
        if (!validateConfidence(d)) {
            return "LOW_CONFIDENCE";
        }
        MemoryEvidenceKind evidenceKind = MemoryQualityPolicy.parseEvidenceKind(d.evidenceKindRaw());
        if (evidenceKind == MemoryEvidenceKind.UNKNOWN) {
            return "UNKNOWN_EVIDENCE";
        }
        if (evidenceKind == MemoryEvidenceKind.CHAT_INFERRED
                && d.extractionConfidence() < 0.60f) {
            return "INFERRED_LOW_TRUST";
        }
        return null;
    }

    private void requireDecisionContract(AudnDecision d) {
        if (d.entityType() == null) {
            log.debug("ExtractionValidator: 丢弃缺少 entityType 的决策, entityName={}", d.entityName());
            throw new IllegalStateException("AUDN 决策 entityType 不能为空: entity=" + d.entityName());
        }
        requireEntityName(d);
        if (d.operation() != AudnOperation.DELETE) {
            requireDescription(d);
        }
        requireScores(d);
        requireTemporality(d);
        if (d.evidenceKindRaw() == null || d.evidenceKindRaw().isBlank()) {
            throw new IllegalStateException("AUDN 决策 evidenceKind 不能为空: entity=" + d.entityName());
        }
        MemoryEvidenceKind evidenceKind = MemoryQualityPolicy.parseEvidenceKind(d.evidenceKindRaw());
        if ((evidenceKind == MemoryEvidenceKind.CHAT_INFERRED || evidenceKind == MemoryEvidenceKind.USER_EXPLICIT)
                && (d.evidenceExcerpt() == null || d.evidenceExcerpt().isBlank())) {
            throw new IllegalStateException("AUDN 决策 evidenceExcerpt 不能为空: entity=" + d.entityName());
        }
    }

    private void requireScores(AudnDecision d) {
        if (d.extractionConfidence() == null) {
            log.debug("ExtractionValidator: 丢弃缺少 extractionConfidence 的决策, entityName={}", d.entityName());
            throw new IllegalStateException("AUDN 决策 extractionConfidence 不能为空: entity=" + d.entityName());
        }
        if (!isScoreInRange(d.extractionConfidence())) {
            log.debug("ExtractionValidator: 丢弃越界 extractionConfidence 的决策, entityName={}, confidence={}",
                    d.entityName(), d.extractionConfidence());
            throw new IllegalStateException(
                    "AUDN 决策 extractionConfidence 越界: entity=%s, value=%s"
                            .formatted(d.entityName(), d.extractionConfidence()));
        }
        if (d.importanceScore() == null) {
            log.debug("ExtractionValidator: 丢弃缺少 importanceScore 的决策, entityName={}", d.entityName());
            throw new IllegalStateException("AUDN 决策 importanceScore 不能为空: entity=" + d.entityName());
        }
        if (!isScoreInRange(d.importanceScore())) {
            log.debug("ExtractionValidator: 丢弃越界 importanceScore 的决策, entityName={}, importance={}",
                    d.entityName(), d.importanceScore());
            throw new IllegalStateException(
                    "AUDN 决策 importanceScore 越界: entity=%s, value=%s"
                            .formatted(d.entityName(), d.importanceScore()));
        }
    }

    private boolean isScoreInRange(float score) {
        return score >= 0.0f && score <= 1.0f;
    }

    private void requireTemporality(AudnDecision d) {
        String raw = d.temporalityRaw();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("AUDN 决策 temporality 不能为空: entity=" + d.entityName());
        }
        if (!raw.equals(raw.trim())) {
            throw new IllegalStateException(
                    "AUDN 决策 temporality 不能包含首尾空白: entity=%s, temporality=%s"
                            .formatted(d.entityName(), raw));
        }
        try {
            Temporality.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            log.debug("ExtractionValidator: 丢弃非法 temporality 决策, entityName={}, temporality={}",
                    d.entityName(), raw);
            throw new IllegalStateException(
                    "AUDN 决策 temporality 非法: entity=%s, temporality=%s".formatted(d.entityName(), raw), ex);
        }
    }

    public record ValidationResult(
            List<AudnDecision> validDecisions,
            List<RejectedDecision> rejectedDecisions
    ) {}

    public record RejectedDecision(
            AudnDecision decision,
            String reason
    ) {}
}
