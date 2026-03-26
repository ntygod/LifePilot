package com.lifepilot.permission.service;

import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.tool.semantics.ToolScopeNormalizer;
import org.springframework.lang.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权作用域匹配器。
 *
 * @author zsg
 * @since 2026-03-25
 */
final class PermissionScopeMatcher {

    private static final Set<String> PATH_KEYS = Set.of("path", "paths", "workspacePath", "workspacePaths", "repoPath", "repoPaths");
    private static final Set<String> ORIGIN_KEYS = Set.of("origin", "origins");
    private static final Set<String> EXACT_IGNORE_CASE_KEYS = Set.of(
            "host", "hosts",
            "collection", "collections",
            "taskId", "taskIds",
            "sessionId", "sessionIds",
            "documentId", "documentIds",
            "integrationId", "integrationIds"
    );

    private PermissionScopeMatcher() {
    }

    static boolean matches(ExecutionGrantScope grantScope, ExecutionGrantScope requestScope) {
        if (grantScope == null || grantScope.isEmpty()) {
            return true;
        }
        Map<String, List<String>> normalizedGrant = normalizeScope(grantScope);
        Map<String, List<String>> normalizedRequest = normalizeScope(requestScope);
        for (Map.Entry<String, List<String>> entry : normalizedGrant.entrySet()) {
            List<String> requestValues = normalizedRequest.get(entry.getKey());
            if (requestValues == null || requestValues.isEmpty()) {
                continue;
            }
            if (!coversDimension(entry.getKey(), entry.getValue(), requestValues)) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, List<String>> normalizeScope(@Nullable ExecutionGrantScope scope) {
        if (scope == null || scope.isEmpty()) {
            return Map.of();
        }
        var normalized = new java.util.LinkedHashMap<String, List<String>>();
        scope.values().forEach((rawKey, rawValue) -> {
            String key = normalizeKey(rawKey);
            List<String> values = normalizeValues(key, ExecutionGrantScope.toStringList(rawValue));
            if (!values.isEmpty()) {
                normalized.put(key, values);
            }
        });
        return Map.copyOf(normalized);
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return switch (key) {
            case "path" -> "paths";
            case "workspacePath" -> "workspacePaths";
            case "repoPath" -> "repoPaths";
            case "origin" -> "origins";
            case "host" -> "hosts";
            case "collection" -> "collections";
            case "taskId" -> "taskIds";
            case "sessionId" -> "sessionIds";
            case "documentId" -> "documentIds";
            case "integrationId" -> "integrationIds";
            default -> key;
        };
    }

    private static List<String> normalizeValues(String key, List<String> rawValues) {
        var normalized = new LinkedHashSet<String>();
        for (String value : rawValues) {
            String normalizedValue = normalizeValue(key, value);
            if (normalizedValue != null && !normalizedValue.isBlank()) {
                normalized.add(normalizedValue);
            }
        }
        return List.copyOf(normalized);
    }

    @Nullable
    private static String normalizeValue(String key, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (PATH_KEYS.contains(key)) {
            return ToolScopeNormalizer.normalizePath(value);
        }
        if (ORIGIN_KEYS.contains(key)) {
            return ToolScopeNormalizer.normalizeOrigin(value);
        }
        return value.trim();
    }

    private static boolean coversDimension(String key, List<String> grantValues, List<String> requestValues) {
        if (grantValues == null || grantValues.isEmpty()) {
            return true;
        }
        if (requestValues == null || requestValues.isEmpty()) {
            return false;
        }
        for (String requestValue : requestValues) {
            boolean matched = grantValues.stream().anyMatch(grantValue -> matchesValue(key, grantValue, requestValue));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesValue(String key, String grantValue, String requestValue) {
        if (PATH_KEYS.contains(key)) {
            return ToolScopeNormalizer.pathStartsWith(requestValue, grantValue);
        }
        if (ORIGIN_KEYS.contains(key) || EXACT_IGNORE_CASE_KEYS.contains(key)) {
            return requestValue.equalsIgnoreCase(grantValue);
        }
        return requestValue.equals(grantValue);
    }
}
