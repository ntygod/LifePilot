package com.lifepilot.memory.store.entity;

import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.semantic.ConflictDetail;
import com.lifepilot.memory.semantic.ConflictResolution;
import com.lifepilot.memory.semantic.MergeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * 版本合并器 — 将新实体信息与已有实体合并生成新版本。
 *
 * <p>合并规则：
 * <ul>
 *   <li>新属性直接添加</li>
 *   <li>用户显式更新（USER_EXPLICIT / USER_CONFIRMED）时，新值优先覆盖旧值</li>
 *   <li>其他情况下冲突属性按 extractionConfidence 决定保留</li>
 *   <li>description：用户显式更新时取新值；其他情况取更长者或非空者</li>
 *   <li>无变化时 isNewVersion=false</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class VersionMerger {

    private static final Logger log = LoggerFactory.getLogger(VersionMerger.class);

    /**
     * 合并新实体信息到已有实体。
     *
     * @param existing       已有实体
     * @param incoming       新实体信息
     * @param conversationId 来源会话 ID
     * @return 合并结果
     */
    public MergeResult merge(TemporalEntity existing, TemporalEntity incoming, String conversationId) {
        var conflicts = new LinkedHashMap<String, ConflictDetail>();
        var mergedProperties = new HashMap<>(existing.properties());
        boolean hasChanges = false;

        // 判断 incoming 是否为用户显式更新：用户最新的显式陈述应覆盖旧值
        boolean incomingIsUserExplicit = isUserExplicitEvidence(incoming.evidenceKind());

        // 合并属性
        for (var entry : incoming.properties().entrySet()) {
            String key = entry.getKey();
            Object newValue = entry.getValue();
            Object oldValue = mergedProperties.get(key);

            if (oldValue == null) {
                // 新属性直接添加
                mergedProperties.put(key, newValue);
                hasChanges = true;
            } else if (!Objects.equals(oldValue, newValue)) {
                // 冲突属性解决策略
                ConflictResolution resolution;
                if (incomingIsUserExplicit) {
                    // 用户显式更新：新值优先（用户最新陈述覆盖旧值）
                    resolution = ConflictResolution.KEEP_NEW;
                    mergedProperties.put(key, newValue);
                } else if (incoming.extractionConfidence() > existing.extractionConfidence()) {
                    resolution = ConflictResolution.KEEP_NEW;
                    mergedProperties.put(key, newValue);
                } else if (incoming.extractionConfidence() < existing.extractionConfidence()) {
                    resolution = ConflictResolution.KEEP_OLD;
                    // 保留旧值，不修改
                } else {
                    resolution = ConflictResolution.KEEP_BOTH;
                    // 置信度相同时保留旧值
                }
                conflicts.put(key, new ConflictDetail(key, oldValue, newValue, resolution));
                hasChanges = true;
            }
        }

        // 合并 description
        String mergedDescription = mergeDescription(existing, incoming, incomingIsUserExplicit);
        if (!Objects.equals(mergedDescription, existing.description())) {
            hasChanges = true;
        }

        if (!hasChanges) {
            log.debug("版本合并: 无变化, name={}, type={}", existing.name(), existing.type());
            return new MergeResult(existing, conflicts, false);
        }

        var now = Instant.now();
        var mergedEntity = new TemporalEntity(
                existing.id(),
                existing.type(),
                existing.name(),
                mergedDescription,
                mergedProperties,
                existing.version() + 1,
                true,
                now,
                null,
                conversationId,
                Math.max(existing.extractionConfidence(), incoming.extractionConfidence()),
                Math.max(existing.importanceScore(), incoming.importanceScore()),
                existing.accessCount(),
                existing.lastAccessedAt(),
                existing.createdAt(),
                now,
                incoming.lifecycleState(),
                incoming.lifecycleReason(),
                incoming.expiresAt(),
                incoming.temporality(),
                incoming.succeededBy(),
                incoming.isDerived(),
                incoming.derivationSources(),
                incoming.evidenceKind(),
                incoming.trustLevel(),
                incoming.trustScore(),
                Math.max(existing.evidenceCount(), incoming.evidenceCount()),
                incoming.lastVerifiedAt() != null ? incoming.lastVerifiedAt() : existing.lastVerifiedAt()
        );

        log.debug("版本合并: 创建新版本, name={}, version={}, userExplicit={}",
                mergedEntity.name(), mergedEntity.version(), incomingIsUserExplicit);
        return new MergeResult(mergedEntity, conflicts, true);
    }

    /**
     * 合并 description 策略：
     * - 用户显式更新且 incoming 有内容时：取新值（用户最新陈述覆盖旧值）
     * - 其他情况：取更长者或非空者
     */
    private String mergeDescription(TemporalEntity existing, TemporalEntity incoming,
                                    boolean incomingIsUserExplicit) {
        String existingDesc = existing.description();
        String incomingDesc = incoming.description();

        if (incomingDesc == null || incomingDesc.isBlank()) {
            return existingDesc; // incoming 无内容，保留旧值
        }

        if (incomingIsUserExplicit) {
            // 用户显式更新：新 description 直接覆盖旧值
            return incomingDesc;
        }

        // 非用户显式更新：取更长者
        int existingLen = existingDesc != null ? existingDesc.length() : 0;
        if (incomingDesc.length() > existingLen) {
            return incomingDesc;
        }
        return existingDesc;
    }

    /**
     * 判断 evidenceKind 是否为用户显式来源。
     * USER_EXPLICIT（用户在对话中明确陈述）和 USER_CONFIRMED（用户通过 UI/API 确认）
     * 都视为用户显式更新，应优先采纳新值。
     */
    private static boolean isUserExplicitEvidence(MemoryEvidenceKind evidenceKind) {
        return evidenceKind == MemoryEvidenceKind.USER_EXPLICIT
                || evidenceKind == MemoryEvidenceKind.USER_CONFIRMED;
    }
}
