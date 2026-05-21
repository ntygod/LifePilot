package com.lifepilot.agent.learning.extraction;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.semantic.AudnOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 提取后验证器 — 对 LLM 返回的 AudnDecision 列表执行质量门控。
 *
 * <p>验证规则：
 * <ul>
 *   <li>entityName 非空且长度在 [1, maxEntityNameLength] 之间</li>
 *   <li>ADD 类型的 description 非空且长度 >= minDescriptionLength</li>
 *   <li>extractionConfidence >= minExtractionConfidence</li>
 *   <li>单次提取数量不超过 maxEntitiesPerExtraction</li>
 *   <li>null 或越界的 confidence/importance 修正为 0.5f</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-03-12
 */
public class ExtractionValidator {

    private static final Logger log = LoggerFactory.getLogger(ExtractionValidator.class);
    private static final float DEFAULT_SCORE = 0.5f;

    private final int maxEntitiesPerExtraction;
    private final float minExtractionConfidence;
    private final int maxEntityNameLength;
    private final int minDescriptionLength;

    public ExtractionValidator(MemoryProperties properties) {
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
        if (decisions == null || decisions.isEmpty()) {
            return new ValidationResult(List.of(), List.of());
        }

        // 1. 修正 confidence/importance 字段
        var normalized = decisions.stream()
                .map(this::normalizeScores)
                .toList();

        // 2. 逐条验证
        var valid = new ArrayList<AudnDecision>();
        var rejected = new ArrayList<RejectedDecision>();
        for (var d : normalized) {
            String rejectionReason = rejectionReason(d);
            if (rejectionReason != null) {
                rejected.add(new RejectedDecision(d, rejectionReason));
                continue;
            }
            valid.add(d);
        }

        // 3. 数量限制：按 extractionConfidence 降序保留前 N 条
        if (valid.size() > maxEntitiesPerExtraction) {
            log.warn("AUDN 提取数量超限: original={}, limit={}",
                    valid.size(), maxEntitiesPerExtraction);
            valid.sort(Comparator.comparing(
                    (AudnDecision d) -> d.extractionConfidence() != null
                            ? d.extractionConfidence() : 0f).reversed());
            valid.subList(maxEntitiesPerExtraction, valid.size()).stream()
                    .map(d -> new RejectedDecision(d, "MAX_ENTITIES_EXCEEDED"))
                    .forEach(rejected::add);
            valid = new ArrayList<>(valid.subList(0, maxEntitiesPerExtraction));
        }

        return new ValidationResult(List.copyOf(valid), List.copyOf(rejected));
    }

    /** 修正 null 或越界的 confidence/importance 为默认值 0.5f。 */
    AudnDecision normalizeScores(AudnDecision d) {
        Float confidence = d.extractionConfidence();
        Float importance = d.importanceScore();
        boolean needsFix = false;

        if (confidence == null || confidence < 0.0f || confidence > 1.0f) {
            confidence = DEFAULT_SCORE;
            needsFix = true;
        }
        if (importance == null || importance < 0.0f || importance > 1.0f) {
            importance = DEFAULT_SCORE;
            needsFix = true;
        }

        if (needsFix) {
            return new AudnDecision(d.operation(), d.entityName(), d.entityType(),
                    d.description(), d.properties(), confidence, importance,
                    d.temporalityRaw(), d.expiresAtRaw(), d.evidenceKindRaw(), d.evidenceExcerpt());
        }
        return d;
    }

    private boolean validateEntityName(AudnDecision d) {
        String name = d.entityName();
        if (name == null || name.isBlank()) {
            log.debug("ExtractionValidator: 丢弃空名称决策, operation={}", d.operation());
            return false;
        }
        if (name.length() > maxEntityNameLength) {
            log.debug("ExtractionValidator: 丢弃超长名称决策, name={}, length={}",
                    name.substring(0, Math.min(20, name.length())) + "...", name.length());
            return false;
        }
        return true;
    }

    private boolean validateDescription(AudnDecision d) {
        if (d.operation() != AudnOperation.ADD) {
            return true;
        }
        String desc = d.description();
        if (desc == null || desc.isBlank() || desc.length() < minDescriptionLength) {
            log.debug("ExtractionValidator: 丢弃无描述 ADD 决策, entityName={}",
                    d.entityName());
            return false;
        }
        return true;
    }

    private boolean validateConfidence(AudnDecision d) {
        float confidence = d.extractionConfidence() != null
                ? d.extractionConfidence() : 0f;
        if (confidence < minExtractionConfidence) {
            log.debug("ExtractionValidator: 丢弃低置信度决策, entityName={}, confidence={}",
                    d.entityName(), confidence);
            return false;
        }
        return true;
    }

    @Nullable
    private String rejectionReason(AudnDecision d) {
        if (d.operation() == null) {
            log.debug("ExtractionValidator: 丢弃缺少 operation 的决策, entityName={}", d.entityName());
            return "OPERATION_MISSING";
        }
        if (d.operation() == AudnOperation.NOOP) {
            return "NOOP";
        }
        if (d.operation() == AudnOperation.DELETE) {
            return null;
        }
        if (d.entityType() == null) {
            log.debug("ExtractionValidator: 丢弃缺少 entityType 的决策, entityName={}", d.entityName());
            return "ENTITY_TYPE_MISSING";
        }
        if (!validateEntityName(d)) {
            return "INVALID_ENTITY_NAME";
        }
        if (!validateDescription(d)) {
            return "INVALID_DESCRIPTION";
        }
        if (!validateConfidence(d)) {
            return "LOW_CONFIDENCE";
        }
        if (d.evidenceKindRaw() != null && !d.evidenceKindRaw().isBlank()) {
            MemoryEvidenceKind evidenceKind = MemoryQualityPolicy.parseEvidenceKind(d.evidenceKindRaw());
            if (evidenceKind == MemoryEvidenceKind.UNKNOWN) {
                return "UNKNOWN_EVIDENCE";
            }
            if (evidenceKind == MemoryEvidenceKind.CHAT_INFERRED
                    && (d.evidenceExcerpt() == null || d.evidenceExcerpt().isBlank())) {
                return "INFERRED_WITHOUT_EVIDENCE";
            }
        }
        return null;
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
