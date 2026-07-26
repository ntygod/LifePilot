package com.lifepilot.interaction.web.model;

import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 前端恢复动作请求。
 *
 * @author zsg
 * @since 2026-07-05
 */
public record TurnRecoveryActionRequest(
        @Nullable String id,
        @Nullable String label,
        @Nullable String description,
        @Nullable String mode,
        @Nullable String toolId,
        @Nullable String toolName,
        @Nullable String executionKind,
        @Nullable String action,
        @Nullable String category,
        @Nullable String subjectLabel,
        @Nullable List<String> subjectNames,
        @Nullable String inputSummary,
        @Nullable String inputDetail,
        @Nullable String outputSummary,
        @Nullable String outputDetail,
        @Nullable String workingDirectory,
        @Nullable String generatedFilePath,
        @Nullable String recoveryHint,
        @Nullable List<String> nextActions,
        @Nullable String callId,
        @Nullable Boolean interrupted,
        @Nullable List<Map<String, Object>> artifactRefs,
        @Nullable List<Map<String, Object>> missingCapabilities
) {
    private static final int ARTIFACT_REF_LIMIT = 8;
    private static final int MISSING_CAPABILITY_LIMIT = 6;

    public TurnRecoveryActionRequest {
        id = normalizeText(id);
        label = normalizeText(label);
        description = normalizeText(description);
        mode = normalizeMode(mode);
        toolId = normalizeText(toolId);
        toolName = normalizeText(toolName);
        executionKind = normalizeText(executionKind);
        action = normalizeText(action);
        category = normalizeText(category);
        subjectLabel = normalizeText(subjectLabel);
        subjectNames = normalizeList(subjectNames);
        inputSummary = normalizeText(inputSummary);
        inputDetail = normalizeText(inputDetail);
        outputSummary = normalizeText(outputSummary);
        outputDetail = normalizeText(outputDetail);
        workingDirectory = normalizeText(workingDirectory);
        generatedFilePath = normalizeText(generatedFilePath);
        recoveryHint = normalizeText(recoveryHint);
        nextActions = normalizeList(nextActions);
        callId = normalizeText(callId);
        interrupted = Boolean.TRUE.equals(interrupted) ? Boolean.TRUE : null;
        artifactRefs = normalizeArtifactRefs(artifactRefs);
        missingCapabilities = normalizeMissingCapabilities(missingCapabilities);
    }

    public boolean isEmpty() {
        return id == null
                && label == null
                && description == null
                && mode == null
                && toolId == null
                && toolName == null
                && executionKind == null
                && action == null
                && category == null
                && subjectLabel == null
                && (subjectNames == null || subjectNames.isEmpty())
                && inputSummary == null
                && inputDetail == null
                && outputSummary == null
                && outputDetail == null
                && workingDirectory == null
                && generatedFilePath == null
                && recoveryHint == null
                && (nextActions == null || nextActions.isEmpty())
                && callId == null
                && interrupted == null
                && (artifactRefs == null || artifactRefs.isEmpty())
                && (missingCapabilities == null || missingCapabilities.isEmpty());
    }

    public Map<String, Object> toCheckpointMap() {
        if (!hasCheckpointData()) {
            return Map.of();
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        putIfPresent(checkpoint, "kind", "TOOL_FAILURE");
        putIfPresent(checkpoint, "recoveryActionId", id);
        putIfPresent(checkpoint, "recoveryActionLabel", label);
        putIfPresent(checkpoint, "recoveryActionDescription", description);
        putIfPresent(checkpoint, "recoveryActionMode", mode);
        putIfPresent(checkpoint, "callId", callId);
        putIfPresent(checkpoint, "toolId", toolId);
        putIfPresent(checkpoint, "toolName", toolName);
        putIfPresent(checkpoint, "executionKind", executionKind);
        putIfPresent(checkpoint, "action", action);
        putIfPresent(checkpoint, "failureCategory", category);
        if (Boolean.TRUE.equals(interrupted)) {
            checkpoint.put("interrupted", true);
        }
        putIfPresent(checkpoint, "subjectLabel", subjectLabel);
        if (subjectNames != null && !subjectNames.isEmpty()) {
            checkpoint.put("subjectNames", subjectNames);
        }
        putIfPresent(checkpoint, "inputSummary", inputSummary);
        putIfPresent(checkpoint, "inputDetail", inputDetail);
        putIfPresent(checkpoint, "outputSummary", outputSummary);
        putIfPresent(checkpoint, "outputDetail", outputDetail);
        putIfPresent(checkpoint, "workingDirectory", workingDirectory);
        putIfPresent(checkpoint, "generatedFilePath", generatedFilePath);
        if (artifactRefs != null && !artifactRefs.isEmpty()) {
            checkpoint.put("artifactRefs", artifactRefs);
        }
        if (missingCapabilities != null && !missingCapabilities.isEmpty()) {
            checkpoint.put("missingCapabilities", missingCapabilities);
        }
        return checkpoint.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(checkpoint));
    }

    private boolean hasCheckpointData() {
        return callId != null
                || Boolean.TRUE.equals(interrupted)
                || toolId != null
                || toolName != null
                || executionKind != null
                || action != null
                || category != null
                || subjectLabel != null
                || (subjectNames != null && !subjectNames.isEmpty())
                || inputSummary != null
                || inputDetail != null
                || outputSummary != null
                || outputDetail != null
                || workingDirectory != null
                || generatedFilePath != null
                || (artifactRefs != null && !artifactRefs.isEmpty())
                || (missingCapabilities != null && !missingCapabilities.isEmpty());
    }

    private static void putIfPresent(Map<String, Object> target, String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    @Nullable
    private static String normalizeText(@Nullable String value) {
        return value != null && !value.isBlank() ? value.strip() : null;
    }

    @Nullable
    private static String normalizeMode(@Nullable String value) {
        String text = normalizeText(value);
        return text != null ? text.toLowerCase(Locale.ROOT) : null;
    }

    @Nullable
    private static List<String> normalizeList(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> normalized = values.stream()
                .map(TurnRecoveryActionRequest::normalizeText)
                .filter(item -> item != null && !item.isBlank())
                .toList();
        if (normalized.isEmpty()) {
            return null;
        }
        return List.copyOf(new LinkedHashSet<>(normalized));
    }

    @Nullable
    private static List<Map<String, Object>> normalizeArtifactRefs(
            @Nullable List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> seenArtifactIds = new HashSet<>();
        List<Map<String, Object>> normalized = values.stream()
                .map(TurnRecoveryActionRequest::normalizeArtifactRef)
                .filter(item -> item != null && !item.isEmpty())
                .filter(item -> seenArtifactIds.add(String.valueOf(item.get("artifactId"))))
                .limit(ARTIFACT_REF_LIMIT)
                .toList();
        return normalized.isEmpty() ? null : List.copyOf(normalized);
    }

    @Nullable
    private static Map<String, Object> normalizeArtifactRef(@Nullable Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String artifactId = firstTextValue(value, "artifactId", "artifact_id", "id");
        if (artifactId == null) {
            return null;
        }
        String type = firstTextValue(value, "type");
        String mimeType = firstTextValue(
                value,
                "mimeType",
                "mime_type",
                "contentType",
                "content_type",
                "mediaType",
                "media_type");
        if (mimeType == null && type != null && type.contains("/")) {
            mimeType = type;
        }
        String kind = firstTextValue(value, "kind");
        if (kind == null && type != null && !type.contains("/")) {
            kind = type;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("artifactId", artifactId);
        putIfPresent(normalized, "fileName", firstTextValue(value, "fileName", "file_name", "filename", "name"));
        putIfPresent(normalized, "mimeType", mimeType);
        putIfPresent(normalized, "kind", normalizeArtifactKind(kind, mimeType));
        String downloadUrl = firstTextValue(value, "downloadUrl", "download_url", "url");
        normalized.put("downloadUrl", downloadUrl != null
                ? downloadUrl
                : "/api/artifacts/" + artifactId + "/download");
        Long size = artifactSize(value.get("size"));
        if (size != null) {
            normalized.put("size", size);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    @Nullable
    private static List<Map<String, Object>> normalizeMissingCapabilities(
            @Nullable List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Set<String> seenIds = new HashSet<>();
        List<Map<String, Object>> normalized = values.stream()
                .map(TurnRecoveryActionRequest::normalizeMissingCapability)
                .filter(item -> item != null && !item.isEmpty())
                .filter(item -> seenIds.add(String.valueOf(item.get("id"))))
                .limit(MISSING_CAPABILITY_LIMIT)
                .toList();
        return normalized.isEmpty() ? null : List.copyOf(normalized);
    }

    @Nullable
    private static Map<String, Object> normalizeMissingCapability(@Nullable Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String id = firstTextValue(value, "id", "toolId", "tool_id", "name");
        if (id == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        String kind = firstTextValue(value, "kind");
        normalized.put("kind", kind != null ? kind : "TOOL");
        normalized.put("id", id);
        putIfPresent(normalized, "source", firstTextValue(value, "source"));
        putIfPresent(normalized, "reason", firstTextValue(value, "reason"));
        return Collections.unmodifiableMap(new LinkedHashMap<>(normalized));
    }

    @Nullable
    private static String objectText(@Nullable Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    @Nullable
    private static String firstTextValue(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            String text = normalizeText(objectText(value.get(key)));
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    @Nullable
    private static String normalizeArtifactKind(@Nullable String kind, @Nullable String mimeType) {
        String text = normalizeText(kind);
        if (text != null) {
            String upper = text.toUpperCase(Locale.ROOT);
            if ("IMAGE".equals(upper) || "FILE".equals(upper)) {
                return upper;
            }
            return text;
        }
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/")
                ? "IMAGE"
                : null;
    }

    @Nullable
    private static Long artifactSize(@Nullable Object value) {
        if (value instanceof Number number && number.longValue() >= 0) {
            return number.longValue();
        }
        String text = normalizeText(objectText(value));
        if (text == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(text);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
