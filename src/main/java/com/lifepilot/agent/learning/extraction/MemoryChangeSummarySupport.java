package com.lifepilot.agent.learning.extraction;

import com.lifepilot.memory.store.entity.EntityType;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 记忆沉淀摘要构建工具。
 *
 * <p>主对话实时事件、历史消息接口和记忆控制器共用同一份摘要格式，
 * 避免“刚沉淀时能看到，刷新后格式变了或消失”。</p>
 *
 * @author zsg
 * @since 2026-07-04
 */
public final class MemoryChangeSummarySupport {

    private MemoryChangeSummarySupport() {
    }

    public static List<Map<String, Object>> buildAppliedMemoryChangeSummaries(
            MemoryExtractionCandidateRepository repository,
            @Nullable String turnId,
            int limit) {
        Objects.requireNonNull(repository, "记忆候选仓库不能为空");
        if (turnId == null || turnId.isBlank()) {
            return List.of();
        }
        return toSummaries(repository.findAppliedMemoryChangesByTurnId(turnId, limit));
    }

    public static List<Map<String, Object>> buildAppliedMemoryChangeSummaries(
            MemoryExtractionCandidateRepository repository,
            @Nullable String turnId,
            @Nullable List<String> targetSpaceIds,
            boolean includeDefaultSpace,
            int limit) {
        Objects.requireNonNull(repository, "记忆候选仓库不能为空");
        if (turnId == null || turnId.isBlank()) {
            return List.of();
        }
        var changes = repository.findAppliedMemoryChangesByTurnId(
                turnId, limit, targetSpaceIds, includeDefaultSpace);
        return toSummaries(changes);
    }

    private static List<Map<String, Object>> toSummaries(
            List<MemoryExtractionCandidateRepository.AppliedMemoryChange> changes) {
        if (changes.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<Map<String, Object>>();
        var seenEntityIds = new LinkedHashSet<String>();
        for (var change : changes) {
            if (change.persistedEntityId() == null || change.persistedEntityId().isBlank()) {
                continue;
            }
            if (!seenEntityIds.add(change.persistedEntityId())) {
                continue;
            }
            result.add(toSummary(change));
        }
        return Collections.unmodifiableList(result);
    }

    public static Map<String, Object> toSummary(
            MemoryExtractionCandidateRepository.AppliedMemoryChange change) {
        Objects.requireNonNull(change, "记忆变更不能为空");
        var source = new LinkedHashMap<String, Object>();
        source.put("type", "memory");
        source.put("id", change.persistedEntityId());
        source.put("name", change.entityName());

        var extra = new LinkedHashMap<String, Object>();
        extra.put("candidateId", change.candidateId());
        if (change.targetSpaceId() != null && !change.targetSpaceId().isBlank()) {
            extra.put("spaceId", change.targetSpaceId());
            extra.put("sourceKind", "project");
            extra.put("sourceKindLabel", "项目上下文");
        } else {
            extra.put("sourceKind", "personal");
            extra.put("sourceKindLabel", "个人上下文");
        }
        extra.put("operation", change.operation());
        extra.put("operationLabel", operationLabel(change.operation()));
        extra.put("entityType", change.entityType());
        extra.put("entityTypeLabel", entityTypeLabel(change.entityType()));
        if (change.description() != null && !change.description().isBlank()) {
            extra.put("description", change.description());
        }
        if (change.importanceScore() != null) {
            extra.put("importanceScore", change.importanceScore());
        }
        if (change.temporality() != null && !change.temporality().isBlank()) {
            extra.put("temporality", change.temporality());
        }
        if (change.expiresAt() != null && !change.expiresAt().isBlank()) {
            extra.put("expiresAt", change.expiresAt());
        }
        extra.put("evidenceKind", change.evidenceKind());
        extra.put("trustLevel", change.trustLevel());
        extra.put("trustScore", change.trustScore());
        if (change.evidenceExcerpt() != null && !change.evidenceExcerpt().isBlank()) {
            extra.put("evidenceExcerpt", change.evidenceExcerpt());
        }
        extra.put("createdAt", change.createdAt().toString());
        source.put("extra", Collections.unmodifiableMap(extra));
        return Collections.unmodifiableMap(source);
    }

    private static String operationLabel(@Nullable String operation) {
        if ("ADD".equals(operation)) {
            return "新增";
        }
        if ("UPDATE".equals(operation)) {
            return "更新";
        }
        if ("DELETE".equals(operation)) {
            return "忘记";
        }
        return "变更";
    }

    private static String entityTypeLabel(@Nullable String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return "记忆";
        }
        try {
            return EntityType.valueOf(entityType).label();
        } catch (IllegalArgumentException ignored) {
            return "记忆";
        }
    }
}
