package com.lifepilot.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TaskCheckpoint 表示压缩后的最小任务状态包。
 *
 * <p>该结构与具体领域无关，只保留续跑所需的通用状态：
 * 目标、阶段、已完成事项、未决事项、决策、约束、产物引用和恢复计划。</p>
 *
 * @author zsg
 * @since 2026-03-27
 */
public record TaskCheckpoint(
        @Nullable String goal,
        @Nullable String currentPhase,
        List<String> completedItems,
        List<String> openItems,
        List<String> decisions,
        List<String> constraints,
        List<ArtifactRef> artifacts,
        List<String> resumePlan,
        List<String> risks,
        List<String> neededContextRefs,
        Map<String, Object> domainState
) {

    public TaskCheckpoint {
        completedItems = sanitizeList(completedItems);
        openItems = sanitizeList(openItems);
        decisions = sanitizeList(decisions);
        constraints = sanitizeList(constraints);
        artifacts = artifacts == null ? List.of() : artifacts.stream()
                .filter(Objects::nonNull)
                .filter(ArtifactRef::hasContent)
                .toList();
        resumePlan = sanitizeList(resumePlan);
        risks = sanitizeList(risks);
        neededContextRefs = sanitizeList(neededContextRefs);
        domainState = domainState == null ? Map.of() : Map.copyOf(domainState);
    }

    public static TaskCheckpoint empty() {
        return new TaskCheckpoint(null, null, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    public boolean hasContent() {
        return hasText(goal)
                || hasText(currentPhase)
                || !completedItems.isEmpty()
                || !openItems.isEmpty()
                || !decisions.isEmpty()
                || !constraints.isEmpty()
                || !artifacts.isEmpty()
                || !resumePlan.isEmpty()
                || !risks.isEmpty()
                || !neededContextRefs.isEmpty()
                || !domainState.isEmpty();
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (hasText(goal)) {
            payload.put("goal", goal.trim());
        }
        if (hasText(currentPhase)) {
            payload.put("currentPhase", currentPhase.trim());
        }
        if (!completedItems.isEmpty()) {
            payload.put("completedItems", completedItems);
        }
        if (!openItems.isEmpty()) {
            payload.put("openItems", openItems);
        }
        if (!decisions.isEmpty()) {
            payload.put("decisions", decisions);
        }
        if (!constraints.isEmpty()) {
            payload.put("constraints", constraints);
        }
        if (!artifacts.isEmpty()) {
            payload.put("artifacts", artifacts.stream()
                    .map(ArtifactRef::toPayload)
                    .toList());
        }
        if (!resumePlan.isEmpty()) {
            payload.put("resumePlan", resumePlan);
        }
        if (!risks.isEmpty()) {
            payload.put("risks", risks);
        }
        if (!neededContextRefs.isEmpty()) {
            payload.put("neededContextRefs", neededContextRefs);
        }
        if (!domainState.isEmpty()) {
            payload.put("domainState", domainState);
        }
        return Map.copyOf(payload);
    }

    public static TaskCheckpoint fromPayload(@Nullable Object rawPayload, ObjectMapper objectMapper) {
        if (rawPayload == null) {
            return TaskCheckpoint.empty();
        }
        try {
            Map<String, Object> payload = objectMapper.convertValue(rawPayload, Map.class);
            if (payload == null || payload.isEmpty()) {
                return TaskCheckpoint.empty();
            }
            List<ArtifactRef> artifacts = objectMapper.convertValue(
                    payload.get("artifacts"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ArtifactRef.class)
            );
            Map<String, Object> domainState = objectMapper.convertValue(
                    payload.get("domainState"),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
            );
            return new TaskCheckpoint(
                    trimToNull(payload.get("goal")),
                    trimToNull(payload.get("currentPhase")),
                    objectMapper.convertValue(
                            payload.get("completedItems"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    ),
                    objectMapper.convertValue(
                            payload.get("openItems"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    ),
                    objectMapper.convertValue(
                            payload.get("decisions"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    ),
                    objectMapper.convertValue(
                            payload.get("constraints"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    ),
                    artifacts != null ? artifacts : List.of(),
                    objectMapper.convertValue(
                            payload.get("resumePlan"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    ),
                    objectMapper.convertValue(
                            payload.get("risks"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    ),
                    objectMapper.convertValue(
                            payload.get("neededContextRefs"),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
                    ),
                    domainState != null ? domainState : Map.of()
            );
        } catch (IllegalArgumentException e) {
            return TaskCheckpoint.empty();
        }
    }

    public record ArtifactRef(
            @Nullable String type,
            @Nullable String title,
            @Nullable String summary,
            @Nullable String refId
    ) {
        boolean hasContent() {
            return hasText(type) || hasText(title) || hasText(summary) || hasText(refId);
        }

        Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (hasText(type)) {
                payload.put("type", type.trim());
            }
            if (hasText(title)) {
                payload.put("title", title.trim());
            }
            if (hasText(summary)) {
                payload.put("summary", summary.trim());
            }
            if (hasText(refId)) {
                payload.put("refId", refId.trim());
            }
            return Map.copyOf(payload);
        }
    }

    private static List<String> sanitizeList(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    @Nullable
    private static String trimToNull(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean hasText(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}
